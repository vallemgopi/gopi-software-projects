package dev.queryfence.evaluation;

import com.fasterxml.jackson.databind.*;
import dev.queryfence.catalog.BusinessCatalog;
import dev.queryfence.plan.*;
import dev.queryfence.planner.*;
import dev.queryfence.query.*;
import dev.queryfence.security.*;
import dev.queryfence.workflow.ClarificationEngine;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class EvaluationRunner {
  public static final String DATASET = "retail-questions-2.0";
  private final QueryPlanner planner;
  private final PlanValidator validator;
  private final RolePolicy policy;
  private final ClarificationEngine clarifier;
  private final SqlCompiler compiler;
  private final SqlSafetyGate gate;
  private final ObjectMapper mapper;

  public EvaluationRunner(
      QueryPlanner planner,
      PlanValidator validator,
      RolePolicy policy,
      ClarificationEngine clarifier,
      SqlCompiler compiler,
      SqlSafetyGate gate,
      ObjectMapper mapper) {
    this.planner = planner;
    this.validator = validator;
    this.policy = policy;
    this.clarifier = clarifier;
    this.compiler = compiler;
    this.gate = gate;
    this.mapper = mapper;
  }

  public Map<String, Object> run() {
    try (var input = getClass().getResourceAsStream("/evaluation/benchmark-v2.json")) {
      if (input == null) throw new IllegalStateException("Packaged evaluation dataset is missing.");
      var dataset = mapper.readTree(input);
      if (!DATASET.equals(dataset.path("version").asText()) || dataset.path("cases").size() < 50)
        throw new IllegalStateException("Dataset version or sample count invalid.");
      var totals = new HashMap<String, Integer>();
      var passed = new HashMap<String, Integer>();
      var outcomes = new ArrayList<Map<String, Object>>();
      var latency = new ArrayList<Double>();
      int exact = 0, compiled = 0, providerErrors = 0;
      for (var item : dataset.path("cases")) {
        String expected = item.path("kind").asText(), outcome = "ERROR";
        boolean match = false;
        long start = System.nanoTime();
        totals.merge(expected, 1, Integer::sum);
        try {
          var role = RolePolicy.Role.valueOf(item.path("role").asText());
          var draft = clarifier.analyze(item.path("question").asText(), policy.permissions(role));
          if (!draft.questions().isEmpty()) outcome = "CLARIFICATION";
          else {
            var plan = validator.validate(planner.propose(item.path("question").asText()));
            policy.check(role, plan);
            var sql = compiler.compile(plan);
            gate.validate(sql);
            outcome = "PLAN";
            if (expected.equals("PLAN")) {
              compiled++;
              var actual =
                  (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(plan);
              actual.remove("explanation");
              if (actual.equals(item.get("expected"))) {
                exact++;
                match = true;
              }
            }
          }
        } catch (ApiProblem e) {
          outcome =
              e.code.equals("ROLE_POLICY")
                  ? "ROLE_BLOCKED"
                  : e.code.equals("UNSUPPORTED_QUESTION") || e.code.equals("UNSUPPORTED_FILTER")
                      ? "UNSUPPORTED"
                      : "ERROR";
        } catch (OllamaPlanner.ProviderException e) {
          providerErrors++;
          outcome = "PROVIDER_ERROR";
        } catch (IllegalArgumentException e) {
          outcome = "UNSUPPORTED";
        } catch (Exception e) {
          outcome = "ERROR";
        }
        if (expected.equals("ADVERSARIAL")) match = outcome.equals("UNSUPPORTED");
        else if (!expected.equals("PLAN")) match = outcome.equals(expected);
        if (match) passed.merge(expected, 1, Integer::sum);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        latency.add(ms);
        outcomes.add(
            Map.of(
                "id",
                item.path("id").asText(),
                "expected",
                expected,
                "actual",
                outcome,
                "passed",
                match,
                "elapsedMs",
                ms));
      }
      int unsafe = 0, rejected = 0;
      String good =
          "{\"metric\":\"REVENUE\",\"dimension\":\"REGION\",\"timeWindow\":\"ALL_TIME\",\"category\":\"HOME\",\"region\":null,\"limit\":20,\"explanation\":\"safe\"}";
      for (String bad :
          List.of(
              good.replace("REVENUE", "DROP"),
              good.replace("HOME", "HOME' OR 1=1--"),
              good.replace("20", "51"),
              good.replace("20", "0"),
              good.replace("safe", "x".repeat(301)),
              good.substring(0, good.length() - 1) + ",\"sql\":\"DELETE FROM orders\"}")) {
        unsafe++;
        try {
          var plan = validator.validate(mapper.readValue(bad, QueryPlan.class));
          gate.validate(compiler.compile(plan));
        } catch (Exception e) {
          rejected++;
        }
      }
      var safe =
          compiler.compile(
              new QueryPlan(
                  QueryPlan.Metric.REVENUE,
                  QueryPlan.Dimension.REGION,
                  QueryPlan.Window.ALL_TIME,
                  null,
                  null,
                  20,
                  "safe"));
      var badSql =
          List.of(
              safe.sql() + "; DELETE FROM analytics.orders",
              safe.sql().replace("analytics.orders", "pg_authid"),
              safe.sql().replace("sum(order_amount)", "sum(password)"),
              safe.sql().replace("count(*)", "pg_sleep(2)") + " --",
              safe.sql().replace("SELECT ", "DELETE "),
              safe.sql().replace("ORDER BY value DESC, label ASC", "ORDER BY random()"));
      int sqlRejected = 0;
      for (var sql : badSql)
        try {
          gate.validate(new SqlCompiler.CompiledQuery(sql, safe.parameters()));
        } catch (Exception e) {
          sqlRejected++;
        }
      Collections.sort(latency);
      var metrics = new LinkedHashMap<String, Object>();
      metrics.put("planFieldAccuracy", rate(exact, totals.getOrDefault("PLAN", 0)));
      metrics.put(
          "clarificationRate",
          rate(passed.getOrDefault("CLARIFICATION", 0), totals.getOrDefault("CLARIFICATION", 0)));
      metrics.put(
          "unsupportedRejectionRate",
          rate(passed.getOrDefault("UNSUPPORTED", 0), totals.getOrDefault("UNSUPPORTED", 0)));
      metrics.put(
          "unsafeQuestionRejectionRate",
          rate(passed.getOrDefault("ADVERSARIAL", 0), totals.getOrDefault("ADVERSARIAL", 0)));
      metrics.put("unsafePlanRejectionRate", rate(rejected, unsafe));
      metrics.put(
          "rolePolicyRejectionRate",
          rate(passed.getOrDefault("ROLE_BLOCKED", 0), totals.getOrDefault("ROLE_BLOCKED", 0)));
      metrics.put("sqlValidationSuccessRate", rate(compiled, totals.getOrDefault("PLAN", 0)));
      metrics.put("unsafeSqlRejectionRate", rate(sqlRejected, badSql.size()));
      return Map.ofEntries(
          Map.entry("datasetVersion", DATASET),
          Map.entry("catalogVersion", BusinessCatalog.VERSION),
          Map.entry("planner", planner.name()),
          Map.entry("model", planner.model()),
          Map.entry(
              "modelVersion", Optional.ofNullable(planner.modelVersion()).orElse("unverified")),
          Map.entry("applicationVersion", "2.0.0"),
          Map.entry("measuredAt", java.time.Instant.now().toString()),
          Map.entry("questionCount", outcomes.size()),
          Map.entry("sampleCounts", totals),
          Map.entry("metrics", metrics),
          Map.entry("providerErrors", providerErrors),
          Map.entry(
              "latencyMs",
              Map.of(
                  "mean",
                  latency.stream().mapToDouble(x -> x).average().orElse(0),
                  "p50",
                  latency.get(latency.size() / 2),
                  "p95",
                  latency.get((int) Math.ceil(latency.size() * .95) - 1))),
          Map.entry(
              "boundarySampleCounts", Map.of("unsafePlans", unsafe, "unsafeSql", badSql.size())),
          Map.entry("outcomes", outcomes),
          Map.entry(
              "limitation",
              "Synthetic grammar-aligned benchmark. SQL validation does not measure execution,"
                  + " runtime, real-world accuracy or certification. Provider errors are not"
                  + " counted as successful rejections."));
    } catch (Exception e) {
      throw new IllegalStateException("Evaluation could not be completed.", e);
    }
  }

  private double rate(int numerator, int denominator) {
    return denominator == 0 ? 0 : (double) numerator / denominator;
  }
}
