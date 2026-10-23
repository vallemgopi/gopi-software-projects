package dev.queryfence.query;

import dev.queryfence.catalog.BusinessCatalog;
import dev.queryfence.plan.QueryPlan;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ResultSummary {
  public record Claim(int row, String label, Object value, String text) {}

  public record Summary(String message, List<Claim> claims, String limitation) {}

  private final BusinessCatalog catalog;

  public ResultSummary(BusinessCatalog catalog) {
    this.catalog = catalog;
  }

  public Summary summarize(QueryPlan plan, List<Map<String, Object>> rows) {
    if (rows.isEmpty())
      return new Summary(
          "No rows were returned. There is insufficient data for a summary.",
          List.of(),
          "No additional queries or actions were performed.");
    var claims = new ArrayList<Claim>();
    for (int i = 0; i < Math.min(3, rows.size()); i++) {
      var r = rows.get(i);
      String label = String.valueOf(r.get("label"));
      Object value = r.get("value");
      claims.add(
          new Claim(
              i + 1,
              label,
              value,
              "Row "
                  + (i + 1)
                  + " ("
                  + label
                  + "): "
                  + catalog.metric(plan.metric()).label()
                  + " = "
                  + value
                  + " "
                  + catalog.metric(plan.metric()).unit()
                  + "."));
    }
    return new Summary(
        "Describing " + claims.size() + " of " + rows.size() + " returned rows.",
        claims,
        (rows.size() < 2 ? "Only one group was returned; insufficient data for comparisons. " : "")
            + "Only returned values are described. No causes, trends or omitted groups can be"
            + " inferred. No additional query was executed.");
  }
}
