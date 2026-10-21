package lab.controller;

import com.fasterxml.jackson.databind.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import lab.domain.*;
import lab.repository.*;
import lab.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LabController {
  private final MlClient ml;
  private final EvaluationRepository evals;
  private final CaseService cases;
  private final ObjectMapper json;

  public LabController(
      MlClient ml, EvaluationRepository evals, CaseService cases, ObjectMapper json) {
    this.ml = ml;
    this.evals = evals;
    this.cases = cases;
    this.json = json;
  }

  @GetMapping("/me")
  public Object me(Principal p) {
    return Map.of("username", p.getName());
  }

  @GetMapping("/health")
  public Object health() {
    return Map.of("status", "ok", "ml", ml.health());
  }

  @PostMapping("/evaluations")
  @Transactional
  public Object evaluate(Principal p) {
    var report = ml.evaluate();
    EvaluationRun run = new EvaluationRun();
    run.id = UUID.randomUUID();
    run.actor = p.getName();
    run.report = report.toString();
    run.createdAt = Instant.now();
    evals.save(run);
    return Map.of("id", run.id, "createdAt", run.createdAt, "report", report);
  }

  @GetMapping("/evaluations/latest")
  public Object latest(Principal p) {
    return evals
        .findFirstByActorOrderByCreatedAtDesc(p.getName())
        .map(r -> Map.of("id", r.id, "createdAt", r.createdAt, "report", cases.decode(r.report)))
        .orElse(Map.of());
  }

  @GetMapping("/metrics")
  public Object metrics(Principal p) {
    var all = cases.list(p.getName(), "");
    long reviewed = all.stream().filter(c -> c.get("status").equals("REVIEWED")).count();
    Map<String, Long> trend = new TreeMap<>();
    Map<String, Long> changed = new TreeMap<>();
    for (var c : all) {
      String day = c.get("createdAt").toString().substring(0, 10);
      trend.merge(day, 1L, Long::sum);
      JsonNode node = json.valueToTree(c);
      var revisions = node.path("corrections");
      if (!revisions.isEmpty()) {
        var latest = revisions.get(revisions.size() - 1).path("fields");
        latest
            .fieldNames()
            .forEachRemaining(
                f -> {
                  var original = node.path("prediction").path("fields").path(f).path("value");
                  String a = original.isNull() ? "" : original.asText();
                  if (!a.equals(latest.path(f).asText(""))) changed.merge(f, 1L, Long::sum);
                });
      }
    }
    return Map.of(
        "cases",
        all.size(),
        "reviewed",
        reviewed,
        "needsReview",
        all.size() - reviewed,
        "casesByDay",
        trend,
        "correctedFields",
        changed);
  }

  @GetMapping("/exports/corrections")
  public Object export(Principal p) {
    return Map.of(
        "schemaVersion",
        "correction-export-v1",
        "notice",
        "User-entered data: obtain consent and de-identify before reuse. Reviewed missing fields"
            + " are empty strings.",
        "cases",
        cases.list(p.getName(), ""));
  }
}
