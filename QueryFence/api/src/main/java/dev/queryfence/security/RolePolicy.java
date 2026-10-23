package dev.queryfence.security;

import dev.queryfence.plan.QueryPlan;
import dev.queryfence.plan.QueryPlan.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RolePolicy {
  public enum Role {
    ANALYST,
    FINANCE,
    OPERATIONS
  }

  public record Permissions(
      Set<Metric> metrics,
      Set<Dimension> dimensions,
      Set<Category> categories,
      Set<Region> regions,
      boolean categoryRequired,
      boolean regionRequired) {}

  public Permissions permissions(Role role) {
    return switch (role) {
      case ANALYST ->
          new Permissions(
              EnumSet.allOf(Metric.class),
              EnumSet.allOf(Dimension.class),
              EnumSet.allOf(Category.class),
              EnumSet.allOf(Region.class),
              false,
              false);
      case FINANCE ->
          new Permissions(
              EnumSet.of(Metric.REVENUE, Metric.ORDER_COUNT),
              EnumSet.of(Dimension.MONTH, Dimension.CATEGORY, Dimension.REGION),
              EnumSet.allOf(Category.class),
              EnumSet.allOf(Region.class),
              false,
              false);
      case OPERATIONS ->
          new Permissions(
              EnumSet.of(Metric.ORDER_COUNT, Metric.RETURN_RATE),
              EnumSet.of(Dimension.CATEGORY, Dimension.REGION, Dimension.STATUS),
              EnumSet.of(Category.HOME, Category.GROCERY),
              EnumSet.of(Region.NORTH, Region.SOUTH),
              true,
              true);
    };
  }

  public void check(Role role, QueryPlan p) {
    var a = permissions(role);
    if (!a.metrics().contains(p.metric())
        || !a.dimensions().contains(p.dimension())
        || (p.category() == null ? a.categoryRequired() : !a.categories().contains(p.category()))
        || (p.region() == null ? a.regionRequired() : !a.regions().contains(p.region())))
      throw new ApiProblem(
          403,
          "ROLE_POLICY",
          "This plan is outside the selected role's metric, grouping or data scope. OPERATIONS"
              + " requires an allowed category and region.");
  }
}
