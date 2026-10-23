package dev.queryfence.workflow;

import dev.queryfence.catalog.BusinessCatalog;
import dev.queryfence.persistence.ControlStore;
import dev.queryfence.plan.*;
import dev.queryfence.planner.QueryPlanner;
import dev.queryfence.query.*;
import dev.queryfence.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsWorkflow {
  public record Review(
      String status,
      UUID planId,
      QueryPlan plan,
      String sql,
      List<Object> parameters,
      Instant expiresAt,
      String planner,
      String model,
      String catalogVersion,
      RolePolicy.Role role,
      int version,
      String reviewDigest,
      CostEstimator.Estimate cost,
      String plannerVersion,
      String modelVersion) {}

  public record Clarification(
      String status,
      UUID clarificationId,
      List<ClarificationEngine.Choice> questions,
      Instant expiresAt) {}

  public record Approval(UUID planId, String approvalToken, Instant expiresAt) {}

  private final QueryPlanner planner;
  private final PlanValidator validator;
  private final SqlCompiler compiler;
  private final SqlSafetyGate gate;
  private final CostEstimator estimator;
  private final QueryExecutor executor;
  private final RolePolicy policy;
  private final BusinessCatalog catalog;
  private final ClarificationEngine clarifier;
  private final ControlStore store;
  private final ResultSummary summaries;

  public AnalyticsWorkflow(
      QueryPlanner planner,
      PlanValidator validator,
      SqlCompiler compiler,
      SqlSafetyGate gate,
      CostEstimator estimator,
      QueryExecutor executor,
      RolePolicy policy,
      BusinessCatalog catalog,
      ClarificationEngine clarifier,
      ControlStore store,
      ResultSummary summaries) {
    this.planner = planner;
    this.validator = validator;
    this.compiler = compiler;
    this.gate = gate;
    this.estimator = estimator;
    this.executor = executor;
    this.policy = policy;
    this.catalog = catalog;
    this.clarifier = clarifier;
    this.store = store;
    this.summaries = summaries;
  }

  public Object plan(Actor actor, String question) {
    actor.require("query:plan");
    long start = System.nanoTime();
    try {
      capacity(actor);
      var draft = clarifier.analyze(question, policy.permissions(actor.role()));
      if (!draft.questions().isEmpty()) {
        UUID id = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(600);
        store.jdbc.update(
            "INSERT INTO"
                + " queryfence.plans(id,actor,role,status,version,clarification,planner,model,catalog_version,expires_at)"
                + " VALUES (?,?,?,'CLARIFICATION_REQUIRED',1,?::jsonb,?,?,?,?)",
            id,
            actor.id(),
            actor.role().name(),
            store.json(draft),
            planner.name(),
            planner.model(),
            BusinessCatalog.VERSION,
            java.sql.Timestamp.from(expiry));
        store.jdbc.update(
            "UPDATE queryfence.plans SET model_version=? WHERE id=?", planner.modelVersion(), id);
        store.audit(
            actor,
            "PLAN",
            id,
            "CLARIFICATION_REQUIRED",
            false,
            Map.of(
                "elapsedMs", elapsed(start), "planner", planner.name(), "model", planner.model()));
        return new Clarification("CLARIFICATION_REQUIRED", id, draft.questions(), expiry);
      }
      return create(actor, planner.propose(question), null, 1, "PLANNED", start);
    } catch (RuntimeException e) {
      store.audit(
          actor,
          "PLAN",
          null,
          "REJECTED",
          false,
          Map.of(
              "elapsedMs",
              elapsed(start),
              "reason",
              e instanceof ApiProblem p ? p.code : "VALIDATION_OR_PROVIDER"));
      throw e;
    }
  }

  public Review clarify(Actor actor, UUID id, Map<String, String> selections) {
    actor.require("query:plan");
    var row = active(actor, id, "CLARIFICATION_REQUIRED");
    var draft = store.decode(row.get("clarification"), ClarificationEngine.Draft.class);
    QueryPlan plan = clarifier.complete(draft, selections);
    policy.check(actor.role(), validator.validate(plan));
    if (store.jdbc.update(
            "UPDATE queryfence.plans SET status='CLARIFIED' WHERE id=? AND"
                + " status='CLARIFICATION_REQUIRED'",
            id)
        != 1) throw conflict();
    return create(actor, plan, id, 1, "CLARIFIED", System.nanoTime());
  }

  public Review edit(Actor actor, UUID id, QueryPlan edited) {
    actor.require("query:plan");
    var row = store.plan(id, actor);
    if (!Set.of("REVIEWED", "BLOCKED", "APPROVED").contains(row.get("status")) || expired(row))
      throw conflict();
    validator.validate(edited);
    policy.check(actor.role(), edited);
    if (store.jdbc.update(
            "UPDATE queryfence.plans SET status='SUPERSEDED',approval_hash=NULL WHERE id=? AND"
                + " status IN ('REVIEWED','BLOCKED','APPROVED')",
            id)
        != 1) throw conflict();
    return create(
        actor,
        edited,
        id,
        ((Number) row.get("version")).intValue() + 1,
        "EDITED",
        System.nanoTime());
  }

  private Review create(
      Actor actor, QueryPlan input, UUID parent, int version, String action, long start) {
    capacity(actor);
    boolean manual = !action.equals("PLANNED");
    String sourcePlanner =
        manual ? (action.equals("EDITED") ? "user-edit" : "user-clarification") : planner.name();
    String sourceModel = manual ? "none (user-selected fields)" : planner.model();
    String sourceModelVersion = manual ? "manual-plan-2.0.0" : planner.modelVersion();
    var plan = catalog.normalizeExplanation(validator.validate(input));
    policy.check(actor.role(), plan);
    var sql = compiler.compile(plan);
    gate.validate(sql);
    var cost = estimator.estimate(sql);
    UUID id = UUID.randomUUID();
    Instant expiry = Instant.now().plusSeconds(600);
    String digest =
        Tokens.hash(
            store.json(plan)
                + "|"
                + actor.id()
                + "|"
                + actor.role()
                + "|"
                + version
                + "|"
                + sql.sql()
                + "|"
                + sql.parameters());
    String status = cost.allowed() ? "REVIEWED" : "BLOCKED";
    store.jdbc.update(
        "INSERT INTO"
            + " queryfence.plans(id,actor,role,status,version,parent_id,plan,planner,model,catalog_version,digest,cost,expires_at,elapsed_ms)"
            + " VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb,?,?)",
        id,
        actor.id(),
        actor.role().name(),
        status,
        version,
        parent,
        store.json(plan),
        sourcePlanner,
        sourceModel,
        BusinessCatalog.VERSION,
        digest,
        store.json(cost),
        java.sql.Timestamp.from(expiry),
        elapsed(start));
    store.jdbc.update(
        "UPDATE queryfence.plans SET model_version=? WHERE id=?", sourceModelVersion, id);
    store.audit(
        actor,
        action,
        id,
        status,
        false,
        Map.of(
            "version",
            version,
            "planner",
            sourcePlanner,
            "model",
            sourceModel,
            "elapsedMs",
            elapsed(start),
            "catalogVersion",
            BusinessCatalog.VERSION));
    return new Review(
        status,
        id,
        plan,
        sql.sql(),
        sql.parameters(),
        expiry,
        sourcePlanner,
        sourceModel,
        BusinessCatalog.VERSION,
        actor.role(),
        version,
        digest,
        cost,
        "2.0.0",
        sourceModelVersion);
  }

  public Review review(Actor actor, UUID id) {
    actor.require("query:plan");
    var row = store.plan(id, actor);
    if (row.get("plan") == null) throw conflict();
    var plan = store.decode(row.get("plan"), QueryPlan.class);
    policy.check(actor.role(), plan);
    var sql = compiler.compile(plan);
    gate.validate(sql);
    verifyCompilation(actor, row, plan, sql);
    return new Review(
        (String) row.get("status"),
        id,
        plan,
        sql.sql(),
        sql.parameters(),
        store.instant(row.get("expires_at")),
        (String) row.get("planner"),
        (String) row.get("model"),
        (String) row.get("catalog_version"),
        actor.role(),
        ((Number) row.get("version")).intValue(),
        (String) row.get("digest"),
        store.decode(row.get("cost"), CostEstimator.Estimate.class),
        (String) row.get("planner_version"),
        (String) row.get("model_version"));
  }

  public Approval approve(Actor actor, UUID id, String digest, boolean confirmed) {
    actor.require("query:review");
    if (!confirmed)
      throw new ApiProblem(
          400,
          "CONFIRMATION_REQUIRED",
          "Explicit confirmation is required after reviewing the current plan.");
    var row = active(actor, id, "REVIEWED");
    if (!Tokens.equal((String) row.get("digest"), digest))
      throw new ApiProblem(
          409, "STALE_REVIEW", "The approval must match the current reviewed plan digest.");
    var plan = validator.validate(store.decode(row.get("plan"), QueryPlan.class));
    policy.check(actor.role(), plan);
    var sql = compiler.compile(plan);
    gate.validate(sql);
    verifyCompilation(actor, row, plan, sql);
    String token = Tokens.create();
    if (store.jdbc.update(
            "UPDATE queryfence.plans SET"
                + " status='APPROVED',approval_hash=?,approved_at=now(),confirmation=true WHERE"
                + " id=? AND status='REVIEWED' AND expires_at>now()",
            Tokens.hash(token),
            id)
        != 1) throw conflict();
    store.audit(actor, "APPROVE", id, "APPROVED", true, Map.of("version", row.get("version")));
    return new Approval(id, token, store.instant(row.get("expires_at")));
  }

  public Map<String, Object> execute(
      Actor actor, UUID id, String approvalToken, boolean confirmed) {
    actor.require("query:execute");
    if (!confirmed || approvalToken == null || approvalToken.length() > 100)
      throw new ApiProblem(
          400,
          "CONFIRMATION_REQUIRED",
          "Supply the existing single-use approval token and explicit confirmation.");
    var row = active(actor, id, "APPROVED");
    var plan = validator.validate(store.decode(row.get("plan"), QueryPlan.class));
    policy.check(actor.role(), plan);
    var sql = compiler.compile(plan);
    gate.validate(sql);
    verifyCompilation(actor, row, plan, sql);
    var estimate = estimator.estimate(sql);
    if (!estimate.allowed()) {
      store.jdbc.update(
          "UPDATE queryfence.plans SET status='BLOCKED',approval_hash=NULL,cost=?::jsonb WHERE"
              + " id=? AND status='APPROVED'",
          store.json(estimate),
          id);
      store.audit(actor, "EXECUTE", id, "COST_BLOCKED", true, Map.of());
      throw new ApiProblem(422, "COST_BUDGET", "Query is above the configured estimate budget.");
    }
    if (store.jdbc.update(
            "UPDATE queryfence.plans SET status='EXECUTING',approval_hash=NULL WHERE id=? AND"
                + " actor=? AND role=? AND status='APPROVED' AND expires_at>now() AND"
                + " approval_hash=?",
            id,
            actor.id(),
            actor.role().name(),
            Tokens.hash(approvalToken))
        != 1) throw conflict();
    long start = System.nanoTime();
    try {
      var rows = executor.execute(sql);
      long ms = elapsed(start);
      var result =
          Map.<String, Object>of(
              "planId",
              id,
              "rows",
              rows,
              "elapsedMs",
              ms,
              "role",
              actor.role(),
              "planner",
              row.get("planner"),
              "model",
              row.get("model"));
      store.jdbc.update(
          "UPDATE queryfence.plans SET status='EXECUTED',result=?::jsonb,elapsed_ms=? WHERE id=?",
          store.json(result),
          ms,
          id);
      store.audit(
          actor, "EXECUTE", id, "EXECUTED", true, Map.of("elapsedMs", ms, "rows", rows.size()));
      return result;
    } catch (RuntimeException e) {
      store.jdbc.update(
          "UPDATE queryfence.plans SET status='FAILED',elapsed_ms=? WHERE id=?",
          elapsed(start),
          id);
      store.audit(actor, "EXECUTE", id, "FAILED", true, Map.of("elapsedMs", elapsed(start)));
      throw e;
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> results(Actor actor, UUID id, boolean summary) {
    actor.require("query:results");
    var row = store.plan(id, actor);
    if (!"EXECUTED".equals(row.get("status")))
      throw new ApiProblem(
          409, "RESULTS_NOT_READY", "No completed results are available for this plan.");
    policy.check(actor.role(), store.decode(row.get("plan"), QueryPlan.class));
    Map<String, Object> result = new LinkedHashMap<>(store.decode(row.get("result"), Map.class));
    if (summary) {
      var rows = (List<Map<String, Object>>) result.get("rows");
      result.put(
          "summary", summaries.summarize(store.decode(row.get("plan"), QueryPlan.class), rows));
      store.audit(actor, "SUMMARIZE", id, "RESULTS_ONLY", true, Map.of("rows", rows.size()));
    }
    return result;
  }

  private Map<String, Object> active(Actor actor, UUID id, String status) {
    var row = store.plan(id, actor);
    if (!status.equals(row.get("status"))) throw conflict();
    if (expired(row)) {
      store.jdbc.update(
          "UPDATE queryfence.plans SET status='EXPIRED',approval_hash=NULL WHERE id=? AND status=?",
          id,
          status);
      store.audit(
          actor, "EXPIRE", id, "EXPIRED", Boolean.TRUE.equals(row.get("confirmation")), Map.of());
      throw new ApiProblem(410, "PLAN_EXPIRED", "Plan expired. Create and review a new plan.");
    }
    return row;
  }

  private void verifyCompilation(
      Actor actor, Map<String, Object> row, QueryPlan plan, SqlCompiler.CompiledQuery sql) {
    String current =
        Tokens.hash(
            store.json(plan)
                + "|"
                + actor.id()
                + "|"
                + actor.role()
                + "|"
                + row.get("version")
                + "|"
                + sql.sql()
                + "|"
                + sql.parameters());
    if (!Tokens.equal((String) row.get("digest"), current)
        || !BusinessCatalog.VERSION.equals(row.get("catalog_version"))) {
      store.jdbc.update(
          "UPDATE queryfence.plans SET status='SUPERSEDED',approval_hash=NULL WHERE id=? AND status"
              + " IN ('REVIEWED','APPROVED','BLOCKED')",
          row.get("id"));
      store.audit(
          actor, "COMPILE_CHECK", (UUID) row.get("id"), "STALE_COMPILATION", false, Map.of());
      throw new ApiProblem(
          409,
          "STALE_REVIEW",
          "The compiled query or catalog changed. Create and review a new plan.");
    }
  }

  private boolean expired(Map<String, Object> row) {
    return !store.instant(row.get("expires_at")).isAfter(Instant.now());
  }

  private ApiProblem conflict() {
    return new ApiProblem(
        409, "PLAN_STATE", "Plan state or approval is invalid, stale, or already consumed.");
  }

  private long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private void capacity(Actor actor) {
    Integer count =
        store.jdbc.queryForObject(
            "SELECT count(*) FROM queryfence.plans WHERE actor=? AND expires_at>now() AND status IN"
                + " ('REVIEWED','APPROVED','CLARIFICATION_REQUIRED')",
            Integer.class,
            actor.id());
    if (count != null && count >= 200)
      throw new ApiProblem(429, "PLAN_CAPACITY", "Too many pending plans for this identity.");
  }
}
