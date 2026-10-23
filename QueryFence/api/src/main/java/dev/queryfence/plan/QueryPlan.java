package dev.queryfence.plan;

import jakarta.validation.constraints.*;

public record QueryPlan(
    @NotNull Metric metric,
    @NotNull Dimension dimension,
    @NotNull Window timeWindow,
    Category category,
    Region region,
    @Min(1) @Max(50) int limit,
    @NotBlank @Size(max = 300) String explanation) {
  public enum Metric {
    REVENUE,
    ORDER_COUNT,
    RETURN_RATE
  }

  public enum Dimension {
    MONTH,
    CATEGORY,
    REGION,
    STATUS
  }

  public enum Window {
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS,
    ALL_TIME
  }

  public enum Category {
    ELECTRONICS,
    HOME,
    CLOTHING,
    GROCERY,
    SPORTS
  }

  public enum Region {
    NORTH,
    SOUTH,
    EAST,
    WEST,
    CENTRAL
  }
}
