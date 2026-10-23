package dev.queryfence.query;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CostEstimator {
  public record Estimate(
      double totalCost,
      long estimatedRows,
      long estimatedScanRows,
      double costBudget,
      long scanRowBudget,
      boolean allowed,
      String note) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final SqlSafetyGate gate;
  private final double maxCost;
  private final long maxRows;

  public CostEstimator(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      SqlSafetyGate gate,
      @Value("${query.max-cost:10000}") double maxCost,
      @Value("${query.max-scan-rows:100000}") long maxRows) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.gate = gate;
    this.maxCost = maxCost;
    this.maxRows = maxRows;
  }

  @Transactional(readOnly = true, timeout = 6)
  public Estimate estimate(SqlCompiler.CompiledQuery query) {
    gate.validate(query);
    String json =
        jdbc.queryForObject(
            "EXPLAIN (FORMAT JSON) " + query.sql(), String.class, query.parameters().toArray());
    return assess(json);
  }

  public Estimate assess(String json) {
    try {
      var plan = mapper.readTree(json).get(0).path("Plan");
      double cost = plan.path("Total Cost").asDouble(Double.NaN);
      long rows = plan.path("Plan Rows").asLong(-1);
      long scans = scanRows(plan);
      boolean allowed =
          Double.isFinite(cost) && cost >= 0 && rows >= 0 && cost <= maxCost && scans <= maxRows;
      return new Estimate(
          cost,
          rows,
          scans,
          maxCost,
          maxRows,
          allowed,
          "PostgreSQL planner estimate only; scan rows are estimated rows at scan nodes after"
              + " filters, not a runtime or physical I/O guarantee.");
    } catch (Exception e) {
      throw new IllegalStateException("Database returned an invalid cost estimate.");
    }
  }

  private long scanRows(JsonNode node) {
    long rows = node.has("Relation Name") ? node.path("Plan Rows").asLong() : 0;
    for (var child : node.path("Plans")) rows += scanRows(child);
    return rows;
  }
}
