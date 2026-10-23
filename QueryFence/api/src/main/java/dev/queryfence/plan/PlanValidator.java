package dev.queryfence.plan;

import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

@Component
public class PlanValidator {
  private final Validator validator;

  public PlanValidator(Validator validator) {
    this.validator = validator;
  }

  public QueryPlan validate(QueryPlan plan) {
    if (plan == null || !validator.validate(plan).isEmpty())
      throw new IllegalArgumentException("Invalid query plan fields or limits.");
    return new QueryPlan(
        plan.metric(),
        plan.dimension(),
        plan.timeWindow(),
        plan.category(),
        plan.region(),
        plan.limit(),
        plan.explanation().strip());
  }
}
