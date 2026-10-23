package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.queryfence.catalog.*;
import dev.queryfence.persistence.*;
import dev.queryfence.plan.*;
import dev.queryfence.planner.*;
import dev.queryfence.query.*;
import dev.queryfence.security.*;
import dev.queryfence.workflow.*;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowBudgetTest {
  @Test
  void blockedEstimateIsPersistedAndNeverExecutes() {
    var jdbc = mock(JdbcTemplate.class);
    var store = new ControlStore(jdbc, new ObjectMapper().findAndRegisterModules());
    var estimator = mock(CostEstimator.class);
    when(estimator.estimate(any()))
        .thenReturn(
            new CostEstimator.Estimate(
                12, 5, 1000, 1, 100, false, "Estimate exceeds configured budget."));
    var executor = mock(QueryExecutor.class);
    var validator = new PlanValidator(Validation.buildDefaultValidatorFactory().getValidator());
    var workflow =
        new AnalyticsWorkflow(
            new DemoPlanner(),
            validator,
            new SqlCompiler(validator),
            new SqlSafetyGate(),
            estimator,
            executor,
            new RolePolicy(),
            new BusinessCatalog(),
            new ClarificationEngine(),
            store,
            new ResultSummary(new BusinessCatalog()));
    var actor =
        new Actor("test", RolePolicy.Role.ANALYST, IntegrationFilter.DEMO_SCOPES, Set.of(), false);
    var review = (AnalyticsWorkflow.Review) workflow.plan(actor, "Revenue by region all time");
    assertEquals("BLOCKED", review.status());
    assertFalse(review.cost().allowed());
    verifyNoInteractions(executor);
  }

  @Test
  void providerCannotChangePlanToRoleForbiddenMetric() {
    var jdbc = mock(JdbcTemplate.class);
    var store = new ControlStore(jdbc, new ObjectMapper().findAndRegisterModules());
    var planner = mock(QueryPlanner.class);
    when(planner.name()).thenReturn("malicious-test");
    when(planner.model()).thenReturn("test");
    when(planner.propose(anyString()))
        .thenReturn(
            new QueryPlan(
                QueryPlan.Metric.RETURN_RATE,
                QueryPlan.Dimension.REGION,
                QueryPlan.Window.ALL_TIME,
                null,
                null,
                20,
                "Allowed-looking but forbidden metric"));
    var estimator = mock(CostEstimator.class);
    var executor = mock(QueryExecutor.class);
    var validator = new PlanValidator(Validation.buildDefaultValidatorFactory().getValidator());
    var workflow =
        new AnalyticsWorkflow(
            planner,
            validator,
            new SqlCompiler(validator),
            new SqlSafetyGate(),
            estimator,
            executor,
            new RolePolicy(),
            new BusinessCatalog(),
            new ClarificationEngine(),
            store,
            new ResultSummary(new BusinessCatalog()));
    var actor =
        new Actor("test", RolePolicy.Role.FINANCE, IntegrationFilter.DEMO_SCOPES, Set.of(), false);
    assertThrows(ApiProblem.class, () -> workflow.plan(actor, "Revenue by region all time"));
    verifyNoInteractions(estimator, executor);
  }
}
