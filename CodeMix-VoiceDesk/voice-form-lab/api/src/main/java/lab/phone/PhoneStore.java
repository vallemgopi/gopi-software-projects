package lab.phone;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import lab.service.CaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhoneStore {
  private final PhoneRepository repo;
  private final JdbcTemplate jdbc;
  private final CaseService cases;
  private final String owner;
  private static final Set<String> TERMINAL = Set.of("COMPLETED", "DECLINED", "FAILED", "EXPIRED");

  public PhoneStore(
      PhoneRepository repo,
      JdbcTemplate jdbc,
      CaseService cases,
      @Value("${lab.demo-user}") String owner) {
    this.repo = repo;
    this.jdbc = jdbc;
    this.cases = cases;
    this.owner = owner;
  }

  private PhoneIntake get(String sid) {
    return repo.findByCallSid(sid)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call session not found."));
  }

  @Transactional
  public PhoneIntake start(String sid) {
    // PostgreSQL transaction lock serializes duplicate initial callbacks, including first
    // insertion.
    jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", sid);
    var existing = repo.findByCallSid(sid);
    if (existing.isPresent()) return existing.get();
    PhoneIntake p = new PhoneIntake();
    p.id = UUID.randomUUID();
    p.callSid = sid;
    p.reference =
        String.valueOf(jdbc.queryForObject("select nextval('phone_reference_seq')", Long.class));
    p.owner = owner;
    p.state = "AWAITING_LANGUAGE";
    p.createdAt = p.updatedAt = p.nextAttemptAt = Instant.now();
    repo.saveAndFlush(p);
    return p;
  }

  @Transactional
  public PhoneIntake language(String sid, String digit) {
    PhoneIntake p = get(sid);
    if (!p.state.equals("AWAITING_LANGUAGE")) return p;
    String code =
        Map.of("1", "ta", "2", "te", "3", "kn", "4", "ml", "5", "en", "6", "hi", "7", "auto")
            .get(digit);
    if (code == null) {
      invalidMenu(p);
      return p;
    }
    p.language = code;
    p.state = "AWAITING_CONSENT";
    p.menuAttempts = 0;
    p.updatedAt = Instant.now();
    return p;
  }

  @Transactional
  public PhoneIntake consent(String sid, String digit) {
    PhoneIntake p = get(sid);
    if (!p.state.equals("AWAITING_CONSENT")) return p;
    if (digit.equals("9")) {
      p.state = "DECLINED";
      p.updatedAt = Instant.now();
      return p;
    }
    if (!digit.equals("1")) {
      invalidMenu(p);
      return p;
    }
    p.consentedAt = Instant.now();
    p.state = "AWAITING_RECORDING";
    p.updatedAt = p.consentedAt;
    return p;
  }

  private void invalidMenu(PhoneIntake p) {
    p.menuAttempts++;
    p.updatedAt = Instant.now();
    if (p.menuAttempts >= 3) {
      p.state = "FAILED";
      p.errorCode = "NO_MENU_SELECTION";
    }
  }

  @Transactional
  public PhoneIntake current(String sid) {
    return get(sid);
  }

  @Transactional
  public void recording(String sid, String recordingSid, String status, int seconds) {
    PhoneIntake p = get(sid);
    if (p.recordingSid != null) {
      if (p.recordingSid.equals(recordingSid)) return;
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A recording was already attached to this call.");
    }
    if (p.consentedAt == null || !p.state.equals("AWAITING_RECORDING"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No consented recording session.");
    p.recordingSid = recordingSid;
    p.updatedAt = Instant.now();
    if (!status.equals("completed") || seconds < 1 || seconds > 60) {
      p.state = "FAILED";
      p.errorCode = status.equals("completed") ? "INVALID_DURATION" : "RECORDING_UNAVAILABLE";
      return;
    }
    p.state = "QUEUED";
    p.nextAttemptAt = Instant.now();
  }

  @Transactional
  public PhoneIntake claim() {
    Instant now = Instant.now();
    var due = repo.findFirstByStateAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("QUEUED", now);
    if (due.isEmpty()) return null;
    PhoneIntake p = due.get();
    p.state = "PROCESSING";
    p.attempts++;
    p.leaseUntil = now.plusSeconds(360);
    p.updatedAt = now;
    repo.flush();
    return p;
  }

  @Transactional
  public void complete(UUID id, int attempt, JsonNode prediction, byte[] audio) {
    PhoneIntake p = repo.locked(id).orElseThrow();
    if (!p.state.equals("PROCESSING") || p.attempts != attempt || p.caseId != null) return;
    var saved =
        cases.createPhone(p.owner, prediction, audio, p.language, p.consentedAt, p.id, p.reference);
    p.caseId = (UUID) saved.get("id");
    p.state = "COMPLETED";
    p.errorCode = null;
    p.leaseUntil = null;
    p.updatedAt = Instant.now();
  }

  @Transactional
  public void failed(UUID id, int attempt, String code, boolean permanent) {
    PhoneIntake p = repo.locked(id).orElseThrow();
    if (!p.state.equals("PROCESSING") || p.attempts != attempt) return;
    p.errorCode = code;
    p.state = permanent || p.attempts >= 5 ? "FAILED" : "QUEUED";
    p.leaseUntil = null;
    p.updatedAt = Instant.now();
    p.nextAttemptAt = p.updatedAt.plusSeconds(30L << (Math.min(p.attempts - 1, 4)));
  }

  @Transactional
  public void recover() {
    Instant now = Instant.now();
    for (var row : repo.findByStateAndLeaseUntilBefore("PROCESSING", now)) {
      PhoneIntake p = repo.locked(row.id).orElseThrow();
      if (!p.state.equals("PROCESSING") || p.leaseUntil.isAfter(now)) continue;
      p.state = p.attempts >= 5 ? "FAILED" : "QUEUED";
      p.errorCode = "WORKER_INTERRUPTED";
      p.nextAttemptAt = now;
      p.updatedAt = now;
      p.leaseUntil = null;
    }
    for (var row :
        repo.findByStateInAndCreatedAtBefore(
            List.of("AWAITING_LANGUAGE", "AWAITING_CONSENT", "AWAITING_RECORDING"),
            now.minusSeconds(3600))) {
      PhoneIntake p = repo.locked(row.id).orElseThrow();
      if (p.state.startsWith("AWAITING_")) {
        p.state = "EXPIRED";
        p.errorCode = "CALL_INCOMPLETE";
        p.updatedAt = now;
      }
    }
  }

  @Transactional(readOnly = true)
  public List<PhoneIntake> cleanupCandidates() {
    List<PhoneIntake> result = new ArrayList<>();
    result.addAll(
        repo.findTop10ByStateAndMediaDeletedFalseAndRecordingSidIsNotNullOrderByUpdatedAtAsc(
            "COMPLETED"));
    result.addAll(
        repo.findTop10ByStateAndMediaDeletedFalseAndRecordingSidIsNotNullOrderByUpdatedAtAsc(
            "FAILED"));
    return result;
  }

  @Transactional
  public void deleted(UUID id) {
    var p = repo.locked(id).orElseThrow();
    p.mediaDeleted = true;
    p.updatedAt = Instant.now();
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(String reviewer) {
    return repo.findTop100ByOwnerOrderByCreatedAtDesc(reviewer).stream()
        .map(
            p -> {
              Map<String, Object> view = new LinkedHashMap<>();
              view.put("id", p.id);
              view.put("reference", p.reference);
              view.put("language", p.language);
              view.put("state", p.state);
              view.put("caseId", p.caseId);
              view.put("attempts", p.attempts);
              view.put("errorCode", p.errorCode);
              view.put("consentedAt", p.consentedAt);
              view.put("createdAt", p.createdAt);
              view.put("updatedAt", p.updatedAt);
              view.put("providerRecordingDeleted", p.mediaDeleted);
              view.put("hasRecording", p.recordingSid != null);
              return view;
            })
        .toList();
  }
}
