package dev.queryfence.query;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class QueryExecutor {
  private final JdbcTemplate jdbc;

  public QueryExecutor(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.jdbc.setQueryTimeout(5);
  }

  @Transactional(readOnly = true, timeout = 6)
  public List<Map<String, Object>> execute(SqlCompiler.CompiledQuery query) {
    new SqlSafetyGate().validate(query);
    return jdbc.queryForList(query.sql(), query.parameters().toArray());
  }
}
