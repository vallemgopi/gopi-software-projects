package dev.queryfence.evaluation;

import dev.queryfence.persistence.ControlStore;
import dev.queryfence.planner.QueryPlanner;
import dev.queryfence.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class EvaluationService implements AutoCloseable {
  private final ControlStore store;
  private final EvaluationRunner runner;
  private final QueryPlanner planner;
  private final ExecutorService pool = Executors.newSingleThreadExecutor();
  private final Semaphore single = new Semaphore(1);

  public EvaluationService(ControlStore store, EvaluationRunner runner, QueryPlanner planner) {
    this.store = store;
    this.runner = runner;
    this.planner = planner;
  }

  public Map<String, Object> run(Actor actor) {
    actor.require("evaluation:run");
    if (!single.tryAcquire())
      throw new ApiProblem(409, "EVALUATION_RUNNING", "An evaluation is already running.");
    UUID id = UUID.randomUUID();
    try {
      store.jdbc.update(
          "INSERT INTO"
              + " queryfence.evaluations(id,actor,status,dataset_version,planner,model,model_version)"
              + " VALUES (?,?,'RUNNING',?,?,?,?)",
          id,
          actor.id(),
          EvaluationRunner.DATASET,
          planner.name(),
          planner.model(),
          planner.modelVersion());
      store.audit(actor, "EVALUATION", null, "STARTED", false, Map.of("runId", id));
      pool.submit(
          () -> {
            try {
              var report = runner.run();
              store.jdbc.update(
                  "UPDATE queryfence.evaluations SET status='COMPLETED',report=?::jsonb WHERE id=?",
                  store.json(report),
                  id);
              store.audit(actor, "EVALUATION", null, "COMPLETED", false, Map.of("runId", id));
            } catch (Exception e) {
              store.jdbc.update("UPDATE queryfence.evaluations SET status='FAILED' WHERE id=?", id);
            } finally {
              single.release();
            }
          });
      return Map.of("id", id, "status", "RUNNING");
    } catch (RuntimeException e) {
      single.release();
      throw e;
    }
  }

  public Map<String, Object> list(Actor actor) {
    actor.require("evaluation:read");
    var runs =
        store
            .jdbc
            .queryForList(
                "SELECT"
                    + " id,status,dataset_version,planner,model,model_version,planner_version,report,baseline,created_at"
                    + " FROM queryfence.evaluations WHERE actor=? ORDER BY created_at DESC LIMIT"
                    + " 20",
                actor.id())
            .stream()
            .map(
                row -> {
                  var value = new LinkedHashMap<>(row);
                  if (value.get("report") != null)
                    value.put("report", store.tree(value.get("report")));
                  return value;
                })
            .toList();
    var baselines =
        store
            .jdbc
            .queryForList(
                "SELECT id,dataset_version,planner,model,model_version,report FROM"
                    + " queryfence.evaluations WHERE actor=? AND baseline=true ORDER BY created_at"
                    + " DESC LIMIT 10",
                actor.id())
            .stream()
            .map(
                row -> {
                  var value = new LinkedHashMap<>(row);
                  value.put("report", store.tree(value.get("report")));
                  return value;
                })
            .toList();
    return Map.of("runs", runs, "baselines", baselines);
  }

  public Map<String, Object> baseline(Actor actor, UUID id) {
    actor.require("evaluation:run");
    var rows =
        store.jdbc.queryForList(
            "SELECT * FROM queryfence.evaluations WHERE id=? AND actor=? AND status='COMPLETED'",
            id,
            actor.id());
    if (rows.isEmpty())
      throw new ApiProblem(
          404, "EVALUATION_NOT_FOUND", "Only a completed owned run can become a baseline.");
    var row = rows.getFirst();
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                store.jdbc.getDataSource()));
    tx.executeWithoutResult(
        s -> {
          store.jdbc.update(
              "UPDATE queryfence.evaluations SET baseline=false WHERE actor=? AND dataset_version=?"
                  + " AND planner=? AND model=? AND model_version=?",
              actor.id(),
              row.get("dataset_version"),
              row.get("planner"),
              row.get("model"),
              row.get("model_version"));
          store.jdbc.update("UPDATE queryfence.evaluations SET baseline=true WHERE id=?", id);
        });
    store.audit(actor, "BASELINE", null, "SAVED", false, Map.of("runId", id));
    return Map.of("id", id, "baseline", true);
  }

  @Override
  public void close() {
    pool.shutdownNow();
  }
}
