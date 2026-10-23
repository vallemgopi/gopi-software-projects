package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.queryfence.plan.*;
import dev.queryfence.plan.QueryPlan.*;
import dev.queryfence.planner.*;
import dev.queryfence.query.*;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;

class PlanSafetyTest {
  final PlanValidator validator =
      new PlanValidator(Validation.buildDefaultValidatorFactory().getValidator());
  final ObjectMapper mapper =
      new ObjectMapper()
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);

  QueryPlan plan(int limit) {
    return new QueryPlan(
        Metric.REVENUE,
        Dimension.REGION,
        Window.LAST_7_DAYS,
        Category.HOME,
        Region.NORTH,
        limit,
        "  explanation  ");
  }

  @Test
  void validatesAndNormalizes() {
    assertEquals("explanation", validator.validate(plan(50)).explanation());
    for (int n : new int[] {0, -1, 51, 999})
      assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(n)));
    assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new QueryPlan(null, Dimension.MONTH, Window.ALL_TIME, null, null, 20, "x")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new QueryPlan(
                    Metric.REVENUE,
                    Dimension.MONTH,
                    Window.ALL_TIME,
                    null,
                    null,
                    20,
                    "x".repeat(301))));
  }

  @Test
  void onlyFixedSqlAndBoundValues() {
    var compiled = new SqlCompiler(validator).compile(plan(5));
    assertTrue(compiled.sql().contains("category = ?"));
    assertFalse(compiled.sql().contains("HOME"));
    assertEquals(
        List.of(LocalDate.of(2026, 1, 25), LocalDate.of(2026, 1, 31), "HOME", "NORTH", 5),
        compiled.parameters());
    assertFalse(compiled.sql().contains("explanation"));
    for (var metric : Metric.values())
      for (var dimension : Dimension.values()) {
        var sql =
            new SqlCompiler(validator)
                .compile(new QueryPlan(metric, dimension, Window.ALL_TIME, null, null, 20, "x"));
        assertEquals(List.of(20), sql.parameters());
        assertTrue(sql.sql().startsWith("SELECT "));
      }
  }

  @Test
  void rejectsUnknownFieldsAndInjection() throws Exception {
    String good = mapper.writeValueAsString(plan(5));
    for (String bad :
        List.of(
            good.replace("REVENUE", "DROP TABLE"),
            good.replace("HOME", "HOME' OR 1=1--"),
            good.substring(0, good.length() - 1) + ",\"sql\":\"DELETE FROM orders\"}",
            good.replace("\"REVENUE\"", "0"))) {
      assertThrows(Exception.class, () -> mapper.readValue(bad, QueryPlan.class));
    }
  }

  @Test
  void rejectsUnsupportedAndAmbiguousQuestions() {
    var demo = new DemoPlanner();
    for (String q :
        List.of(
            "delete all orders",
            "profit by region",
            "revenue",
            "revenue and order count by region",
            "revenue by region yesterday",
            "revenue by region for mars",
            "sales by region where amount > 100",
            "revenue by region; drop table orders"))
      assertThrows(IllegalArgumentException.class, () -> demo.propose(q), q);
    assertEquals(Metric.REVENUE, demo.propose("Revenue by category last 30 days").metric());
  }
}
