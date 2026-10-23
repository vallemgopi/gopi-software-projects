package dev.queryfence.query;

import dev.queryfence.plan.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SqlCompiler {
  private final PlanValidator validator;

  public SqlCompiler(PlanValidator validator) {
    this.validator = validator;
  }

  public record CompiledQuery(String sql, List<Object> parameters) {}

  public CompiledQuery compile(QueryPlan input) {
    var p = validator.validate(input);
    String dimension =
        switch (p.dimension()) {
          case MONTH -> "to_char(order_date, 'YYYY-MM')";
          case CATEGORY -> "category";
          case REGION -> "region";
          case STATUS -> "status";
        };
    String metric = new dev.queryfence.catalog.BusinessCatalog().expression(p.metric());
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + dimension
                + " AS label,\n       "
                + metric
                + " AS value\nFROM analytics.orders\nWHERE true");
    List<Object> params = new ArrayList<>();
    if (p.timeWindow() != QueryPlan.Window.ALL_TIME) {
      int days =
          switch (p.timeWindow()) {
            case LAST_7_DAYS -> 7;
            case LAST_30_DAYS -> 30;
            case LAST_90_DAYS -> 90;
            default -> throw new IllegalStateException();
          };
      // Dataset has a fixed reference date so benchmark and screenshots remain reproducible.
      sql.append("\n  AND order_date >= ? AND order_date <= ?");
      params.add(LocalDate.of(2026, 1, 31).minusDays(days - 1));
      params.add(LocalDate.of(2026, 1, 31));
    }
    if (p.category() != null) {
      sql.append("\n  AND category = ?");
      params.add(p.category().name());
    }
    if (p.region() != null) {
      sql.append("\n  AND region = ?");
      params.add(p.region().name());
    }
    sql.append("\nGROUP BY " + dimension + "\nORDER BY value DESC, label ASC\nLIMIT ?");
    params.add(p.limit());
    return new CompiledQuery(sql.toString(), List.copyOf(params));
  }
}
