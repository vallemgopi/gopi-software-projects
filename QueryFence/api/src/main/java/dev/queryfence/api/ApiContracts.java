package dev.queryfence.api;

import dev.queryfence.catalog.BusinessCatalog;
import dev.queryfence.plan.QueryPlan;
import dev.queryfence.query.ResultSummary;
import dev.queryfence.security.RolePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Explicit published response schemas; no raw SQL input contract exists. */
public final class ApiContracts {
  private ApiContracts() {}

  public record SchemaResponse(
      String catalogVersion,
      List<BusinessCatalog.MetricDefinition> metricDefinitions,
      RolePolicy.Permissions permissions,
      RolePolicy.Role role,
      RolePolicy.Role[] roles,
      QueryPlan.Window[] timeWindows,
      String referenceDate,
      int rows,
      String apiVersion) {}

  public record ResultRow(String label, BigDecimal value) {}

  public record Results(
      UUID planId,
      List<ResultRow> rows,
      long elapsedMs,
      RolePolicy.Role role,
      String planner,
      String model,
      ResultSummary.Summary summary) {}

  public record History(
      UUID id,
      QueryPlan plan,
      String role,
      String status,
      int version,
      String planner,
      String model,
      boolean confirmation,
      Long elapsed_ms,
      Instant created_at) {}

  public record Audit(
      Instant at,
      String request_id,
      String actor,
      String role,
      String action,
      UUID plan_id,
      String outcome,
      boolean approved,
      Map<String, Object> details) {}
}
