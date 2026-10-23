package dev.queryfence.query;

import dev.queryfence.plan.*;
import dev.queryfence.planner.QueryPlanner;
import java.time.*;
import java.util.*;

// Retained for regression tests of the original lifecycle. HTTP routes use the persistent
// AnalyticsWorkflow.
@Deprecated
public class QueryService {
  private final QueryPlanner planner;
  private final PlanValidator validator;
  private final SqlCompiler compiler;
  private final QueryExecutor executor;
  private final Clock clock;
  private final LinkedHashMap<UUID, Review> pending = new LinkedHashMap<>();
  private final LinkedList<Activity> history = new LinkedList<>();

  public record Review(
      UUID planId,
      QueryPlan plan,
      String sql,
      List<Object> parameters,
      Instant expiresAt,
      String planner,
      String model) {}

  public record Activity(
      UUID planId, QueryPlan plan, String status, long elapsedMs, String planner, Instant at) {}

  @org.springframework.beans.factory.annotation.Autowired
  public QueryService(QueryPlanner p, PlanValidator v, SqlCompiler c, QueryExecutor e) {
    this(p, v, c, e, Clock.systemUTC());
  }

  QueryService(QueryPlanner p, PlanValidator v, SqlCompiler c, QueryExecutor e, Clock clock) {
    planner = p;
    validator = v;
    compiler = c;
    executor = e;
    this.clock = clock;
  }

  public Review plan(String question) {
    long start = System.nanoTime();
    QueryPlan p;
    try {
      p = validator.validate(planner.propose(question));
    } catch (RuntimeException e) {
      record(new Activity(null, null, "REJECTED", elapsed(start), planner.name(), clock.instant()));
      throw e;
    }
    var sql = compiler.compile(p);
    var review =
        new Review(
            UUID.randomUUID(),
            p,
            sql.sql(),
            sql.parameters(),
            clock.instant().plusSeconds(600),
            planner.name(),
            planner.model());
    synchronized (this) {
      pending.values().removeIf(r -> !r.expiresAt().isAfter(clock.instant()));
      if (pending.size() >= 200)
        throw new IllegalArgumentException("Pending plan capacity reached. Try later.");
      pending.put(review.planId(), review);
    }
    record(
        new Activity(
            review.planId(), p, "PLANNED", elapsed(start), planner.name(), clock.instant()));
    return review;
  }

  public Map<String, Object> execute(UUID id) {
    Review review;
    synchronized (this) {
      review = pending.remove(id);
    }
    if (review == null || !review.expiresAt().isAfter(clock.instant()))
      throw new IllegalArgumentException(
          "Plan expired, unknown, or already consumed. Request a new plan.");
    long start = System.nanoTime();
    try {
      var rows = executor.execute(compiler.compile(review.plan()));
      long ms = elapsed(start);
      record(new Activity(id, review.plan(), "EXECUTED", ms, review.planner(), clock.instant()));
      return Map.of("rows", rows, "elapsedMs", ms, "planId", id);
    } catch (RuntimeException e) {
      record(
          new Activity(
              id, review.plan(), "FAILED", elapsed(start), review.planner(), clock.instant()));
      throw e;
    }
  }

  private long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private synchronized void record(Activity a) {
    history.addFirst(a);
    while (history.size() > 100) history.removeLast();
  }

  public synchronized List<Activity> history() {
    return List.copyOf(history);
  }
}
