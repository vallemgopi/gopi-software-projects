package dev.queryfence.query;

import dev.queryfence.plan.QueryPlan.*;
import dev.queryfence.security.ApiProblem;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** Independent finite SQL grammar: no generic SQL parser permissiveness, comments or statements. */
@Component
public class SqlSafetyGate {
  private static final String DIM = "(to_char\\(order_date, 'YYYY-MM'\\)|category|region|status)";
  private static final String MET =
      "(?:round\\(sum\\(order_amount\\), 2\\)|count\\(\\*\\)|round\\(avg\\(case when returned then"
          + " 1\\.0 else 0\\.0 end\\) \\* 100, 2\\))";
  private static final Pattern GRAMMAR =
      Pattern.compile(
          "^SELECT "
              + DIM
              + " AS label,\\n       "
              + MET
              + " AS value\\n"
              + "FROM analytics\\.orders\\n"
              + "WHERE true(\\n"
              + "  AND order_date >= \\? AND order_date <= \\?)?(\\n"
              + "  AND category = \\?)?(\\n"
              + "  AND region = \\?)?\\n"
              + "GROUP BY \\1\\n"
              + "ORDER BY value DESC, label ASC\\n"
              + "LIMIT \\?$",
          Pattern.DOTALL);

  public void validate(SqlCompiler.CompiledQuery query) {
    try {
      var match = GRAMMAR.matcher(query.sql());
      if (!match.matches()) throw new IllegalArgumentException();
      var args = query.parameters();
      int i = 0;
      if (match.group(2) != null) {
        if (!(args.get(i++) instanceof LocalDate start)
            || !(args.get(i++) instanceof LocalDate end)
            || start.isAfter(end)
            || !end.equals(LocalDate.of(2026, 1, 31))
            || !Set.of(6L, 29L, 89L)
                .contains(java.time.temporal.ChronoUnit.DAYS.between(start, end)))
          throw new IllegalArgumentException();
      }
      if (match.group(3) != null) Category.valueOf((String) args.get(i++));
      if (match.group(4) != null) Region.valueOf((String) args.get(i++));
      if (!(args.get(i++) instanceof Integer limit) || limit < 1 || limit > 50 || i != args.size())
        throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new ApiProblem(
          422,
          "SQL_SAFETY_GATE",
          "Compiled query did not match the independent SELECT grammar or parameter contract.");
    }
  }
}
