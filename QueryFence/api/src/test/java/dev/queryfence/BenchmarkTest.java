package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.queryfence.plan.*;
import dev.queryfence.plan.QueryPlan.*;
import dev.queryfence.planner.*;
import dev.queryfence.query.SqlCompiler;
import jakarta.validation.Validation;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BenchmarkTest {
  @Test
  void measureBenchmark() throws Exception {
    var mapper =
        new ObjectMapper()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    QueryPlanner planner =
        "ollama".equals(System.getProperty("benchmark.planner"))
            ? new OllamaPlanner(
                System.getProperty("ollama.url", "http://localhost:11434"),
                System.getProperty("ollama.model", "qwen2.5:3b"),
                mapper)
            : new DemoPlanner();
    var validator = new PlanValidator(Validation.buildDefaultValidatorFactory().getValidator());
    var compiler = new SqlCompiler(validator);
    var fixture = mapper.readTree(Files.readString(Path.of("../evaluation/benchmark.json")));
    assertTrue(fixture.size() >= 50);
    int valid = 0,
        exact = 0,
        compiled = 0,
        out = 0,
        rejectedOut = 0,
        ambiguous = 0,
        rejectedAmbiguous = 0;
    var latency = new ArrayList<Double>();
    for (var item : fixture) {
      long start = System.nanoTime();
      QueryPlan plan = null;
      try {
        plan = validator.validate(planner.propose(item.get("question").asText()));
      } catch (RuntimeException ignored) {
      }
      latency.add((System.nanoTime() - start) / 1_000_000.0);
      if (item.get("expected").isNull()) {
        if (item.get("kind").asText().equals("out_of_scope")) {
          out++;
          if (plan == null) rejectedOut++;
        } else {
          ambiguous++;
          if (plan == null) rejectedAmbiguous++;
        }
      } else {
        valid++;
        if (plan != null) {
          JsonNode actual = mapper.valueToTree(plan);
          ((com.fasterxml.jackson.databind.node.ObjectNode) actual).remove("explanation");
          if (actual.equals(item.get("expected"))) exact++;
          try {
            compiler.compile(plan);
            compiled++;
          } catch (RuntimeException ignored) {
          }
        }
      }
    }
    int unsafe = 0, unsafeRejected = 0;
    String baseline =
        mapper.writeValueAsString(
            new QueryPlan(
                Metric.REVENUE,
                Dimension.REGION,
                Window.ALL_TIME,
                Category.HOME,
                null,
                20,
                "safe"));
    for (String bad :
        List.of(
            baseline.replace("REVENUE", "DELETE"),
            baseline.replace("HOME", "HOME' OR true--"),
            baseline.replace("20", "51"),
            baseline.replace("20", "0"),
            baseline.replace("safe", "x".repeat(301)),
            baseline.substring(0, baseline.length() - 1) + ",\"sql\":\"DROP TABLE orders\"}")) {
      unsafe++;
      try {
        compiler.compile(mapper.readValue(bad, QueryPlan.class));
      } catch (Exception e) {
        unsafeRejected++;
      }
    }
    Collections.sort(latency);
    var report = new LinkedHashMap<String, Object>();
    report.put("measuredAt", java.time.Instant.now().toString());
    report.put("planner", planner.name());
    report.put("model", planner.model());
    report.put("questions", fixture.size());
    report.put("validQuestions", valid);
    report.put("exactPlanFieldMatchRate", (double) exact / valid);
    report.put("sqlCompilationSuccessRate", (double) compiled / valid);
    report.put("outOfScopeRejectionRate", (double) rejectedOut / out);
    report.put("ambiguousRejectionRate", (double) rejectedAmbiguous / ambiguous);
    report.put("invalidPlanRejectionRate", (double) unsafeRejected / unsafe);
    report.put("unsafeValueRejectionRate", (double) unsafeRejected / unsafe);
    report.put("unsafeCases", unsafe);
    report.put(
        "latencyMs",
        Map.of(
            "mean",
            latency.stream().mapToDouble(x -> x).average().orElse(0),
            "p50",
            latency.get(latency.size() / 2),
            "p95",
            latency.get((int) Math.ceil(latency.size() * .95) - 1)));
    Files.writeString(
        Path.of("../evaluation/results-" + planner.name() + ".json"),
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    assertEquals(unsafe, unsafeRejected, "Unsafe plans must never compile");
  }
}
