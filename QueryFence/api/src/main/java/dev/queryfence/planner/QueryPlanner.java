package dev.queryfence.planner;

import dev.queryfence.plan.QueryPlan;

public interface QueryPlanner {
  QueryPlan propose(String question);

  String name();

  String model();

  default String modelVersion() {
    return "unverified";
  }
}
