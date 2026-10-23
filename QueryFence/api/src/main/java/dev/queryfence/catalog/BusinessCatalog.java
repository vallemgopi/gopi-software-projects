package dev.queryfence.catalog;

import dev.queryfence.plan.QueryPlan;
import dev.queryfence.plan.QueryPlan.Metric;
import java.util.*;
import org.springframework.stereotype.Component;

/** Business definitions and compiler-owned expressions. Never populated by a model or request. */
@Component
public class BusinessCatalog {
  public static final String VERSION = "retail-semantics-2.0";

  public record MetricDefinition(Metric key, String label, String definition, String unit) {}

  public List<MetricDefinition> metrics() {
    return List.of(
        new MetricDefinition(
            Metric.REVENUE,
            "Revenue",
            "Sum of order amounts across all matching statuses, including pending and cancelled"
                + " orders.",
            "amount (no currency assigned)"),
        new MetricDefinition(
            Metric.ORDER_COUNT,
            "Order count",
            "Number of matching synthetic orders across all statuses.",
            "orders"),
        new MetricDefinition(
            Metric.RETURN_RATE,
            "Return rate",
            "Returned matching orders divided by all matching orders, multiplied by 100. This is"
                + " not a revenue-weighted rate.",
            "percent"));
  }

  public MetricDefinition metric(Metric key) {
    return metrics().stream().filter(m -> m.key() == key).findFirst().orElseThrow();
  }

  public String expression(Metric key) {
    return switch (key) {
      case REVENUE -> "round(sum(order_amount), 2)";
      case ORDER_COUNT -> "count(*)";
      case RETURN_RATE -> "round(avg(case when returned then 1.0 else 0.0 end) * 100, 2)";
    };
  }

  public String promptDefinitions() {
    return metrics().stream()
        .map(m -> m.key() + ": " + m.definition())
        .reduce("Business catalog " + VERSION + ". ", (a, b) -> a + b + "\n");
  }

  public QueryPlan normalizeExplanation(QueryPlan p) {
    return new QueryPlan(
        p.metric(),
        p.dimension(),
        p.timeWindow(),
        p.category(),
        p.region(),
        p.limit(),
        metric(p.metric()).label()
            + " grouped by "
            + p.dimension()
            + "; "
            + p.timeWindow()
            + "; catalog "
            + VERSION
            + ".");
  }
}
