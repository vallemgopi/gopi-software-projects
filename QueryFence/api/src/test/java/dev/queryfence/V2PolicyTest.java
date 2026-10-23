package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import dev.queryfence.catalog.*;
import dev.queryfence.evaluation.*;
import dev.queryfence.plan.*;
import dev.queryfence.plan.QueryPlan.*;
import dev.queryfence.planner.*;
import dev.queryfence.query.*;
import dev.queryfence.security.*;
import dev.queryfence.workflow.*;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.*;

class V2PolicyTest {
  final PlanValidator validator =
      new PlanValidator(Validation.buildDefaultValidatorFactory().getValidator());
  final SqlCompiler compiler = new SqlCompiler(validator);
  final RolePolicy policy = new RolePolicy();
  final ClarificationEngine clarifier = new ClarificationEngine();

  QueryPlan plan(Metric metric, Dimension dim, Category category, Region region) {
    return new QueryPlan(metric, dim, Window.ALL_TIME, category, region, 20, "safe");
  }

  @Test
  void roleDataScopeCannotBeBypassedWithNoFilter() {
    assertThrows(
        ApiProblem.class,
        () ->
            policy.check(
                RolePolicy.Role.FINANCE, plan(Metric.RETURN_RATE, Dimension.MONTH, null, null)));
    assertThrows(
        ApiProblem.class,
        () ->
            policy.check(
                RolePolicy.Role.FINANCE, plan(Metric.REVENUE, Dimension.STATUS, null, null)));
    assertThrows(
        ApiProblem.class,
        () ->
            policy.check(
                RolePolicy.Role.OPERATIONS,
                plan(Metric.ORDER_COUNT, Dimension.REGION, null, null)));
    assertThrows(
        ApiProblem.class,
        () ->
            policy.check(
                RolePolicy.Role.OPERATIONS,
                plan(Metric.ORDER_COUNT, Dimension.REGION, Category.ELECTRONICS, Region.NORTH)));
    assertThrows(
        ApiProblem.class,
        () ->
            policy.check(
                RolePolicy.Role.OPERATIONS,
                plan(Metric.ORDER_COUNT, Dimension.REGION, Category.HOME, Region.WEST)));
    assertDoesNotThrow(
        () ->
            policy.check(
                RolePolicy.Role.OPERATIONS,
                plan(Metric.RETURN_RATE, Dimension.STATUS, Category.HOME, Region.NORTH)));
  }

  @Test
  void ambiguityRequiresExactOfferedSelections() {
    var draft =
        clarifier.analyze(
            "Revenue and order count by region", policy.permissions(RolePolicy.Role.ANALYST));
    assertEquals(
        Set.of("metric", "timeWindow"),
        draft.questions().stream()
            .map(ClarificationEngine.Choice::field)
            .collect(java.util.stream.Collectors.toSet()));
    assertThrows(ApiProblem.class, () -> clarifier.complete(draft, Map.of("metric", "REVENUE")));
    assertThrows(
        ApiProblem.class,
        () -> clarifier.complete(draft, Map.of("metric", "DROP", "timeWindow", "ALL_TIME")));
    assertEquals(
        Metric.REVENUE,
        clarifier.complete(draft, Map.of("metric", "REVENUE", "timeWindow", "ALL_TIME")).metric());
  }

  @Test
  void independentGateRejectsEveryUnsupportedStructure() {
    var gate = new SqlSafetyGate();
    var safe = compiler.compile(plan(Metric.REVENUE, Dimension.REGION, null, null));
    gate.validate(safe);
    for (var bad :
        List.of(
            safe.sql() + ";SELECT 1",
            safe.sql().replace("analytics.orders", "other.orders"),
            safe.sql().replace("order_amount", "password"),
            safe.sql().replace("sum(order_amount)", "pg_sleep(3)"),
            safe.sql() + " -- comment",
            safe.sql().replace("GROUP BY region", "GROUP BY category")))
      assertThrows(
          ApiProblem.class,
          () -> gate.validate(new SqlCompiler.CompiledQuery(bad, safe.parameters())));
    assertThrows(
        ApiProblem.class,
        () -> gate.validate(new SqlCompiler.CompiledQuery(safe.sql(), List.of("20 OR true"))));
  }

  @Test
  void costBudgetBlocksAndNeverUsesAnalyze() {
    var estimator =
        new CostEstimator(
            mock(org.springframework.jdbc.core.JdbcTemplate.class),
            new ObjectMapper(),
            new SqlSafetyGate(),
            10,
            100);
    var okay =
        estimator.assess(
            "[{\"Plan\":{\"Total Cost\":9,\"Plan Rows\":5,\"Plans\":[{\"Relation"
                + " Name\":\"orders\",\"Plan Rows\":90}]}}]");
    assertTrue(okay.allowed());
    assertEquals(90, okay.estimatedScanRows());
    assertFalse(estimator.assess("[{\"Plan\":{\"Total Cost\":11,\"Plan Rows\":5}}]").allowed());
    assertFalse(
        estimator
            .assess(
                "[{\"Plan\":{\"Total Cost\":1,\"Plan Rows\":5,\"Relation Name\":\"orders\",\"Plan"
                    + " Rows\":101}}]")
            .allowed());
  }

  @Test
  void summaryCitesOnlyReturnedRowsAndHandlesEmpty() {
    var service = new ResultSummary(new BusinessCatalog());
    var p = plan(Metric.REVENUE, Dimension.CATEGORY, null, null);
    assertTrue(service.summarize(p, List.of()).claims().isEmpty());
    var summary = service.summarize(p, List.of(Map.of("label", "HOME", "value", 42)));
    assertEquals(1, summary.claims().getFirst().row());
    assertEquals(42, summary.claims().getFirst().value());
    assertTrue(summary.limitation().contains("insufficient"));
  }

  @Test
  void versionedAdversarialDatasetIsBlocked() throws Exception {
    var mapper = new ObjectMapper();
    try (var in = getClass().getResourceAsStream("/evaluation/adversarial-v1.json")) {
      var cases = mapper.readTree(in).get("cases");
      assertTrue(cases.size() >= 10);
      for (var item : cases)
        assertThrows(
            ApiProblem.class,
            () -> clarifier.guard(item.get("question").asText()),
            item.get("id").asText());
    }
  }

  @Test
  void evaluationMeasuresCurrentCodeRatherThanReturningBakedScores() {
    var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    var normal =
        new EvaluationRunner(
                new DemoPlanner(),
                validator,
                policy,
                clarifier,
                compiler,
                new SqlSafetyGate(),
                mapper)
            .run();
    assertEquals(84, normal.get("questionCount"));
    assertEquals("retail-questions-2.0", normal.get("datasetVersion"));
    QueryPlanner broken = mock(QueryPlanner.class);
    when(broken.name()).thenReturn("broken-test");
    when(broken.model()).thenReturn("test");
    when(broken.propose(anyString())).thenThrow(new OllamaPlanner.ProviderException());
    var degraded =
        new EvaluationRunner(
                broken, validator, policy, clarifier, compiler, new SqlSafetyGate(), mapper)
            .run();
    var metrics = (Map<?, ?>) degraded.get("metrics");
    assertEquals(0.0, metrics.get("planFieldAccuracy"));
    assertTrue(((Number) degraded.get("providerErrors")).intValue() > 0);
  }
}
