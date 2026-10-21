package lab.service;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import lab.domain.*;
import lab.dto.Requests.*;
import lab.repository.*;
import lab.validation.ReviewValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CaseService {
  private final CaseRepository cases;
  private final CorrectionRepository corrections;
  private final AuditRepository audits;
  private final MlClient ml;
  private final ObjectMapper json;
  private final ReviewValidator validator;
  private final boolean retain;

  public CaseService(
      CaseRepository c,
      CorrectionRepository r,
      AuditRepository a,
      MlClient ml,
      ObjectMapper j,
      ReviewValidator v,
      @Value("${lab.retain-audio}") boolean retain) {
    cases = c;
    corrections = r;
    audits = a;
    this.ml = ml;
    json = j;
    validator = v;
    this.retain = retain;
  }

  public LabCase owned(UUID id, String owner) {
    return cases
        .findById(id)
        .filter(c -> c.owner.equals(owner))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
  }

  public Object decode(String s) {
    try {
      return json.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored JSON");
    }
  }

  public Map<String, Object> view(LabCase c) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", c.id);
    out.put("transcript", c.transcript);
    out.put("prediction", decode(c.prediction));
    out.put("status", c.status);
    out.put("source", c.source);
    out.put("consentedAt", c.consentedAt);
    out.put("submittedLanguage", c.submittedLanguage);
    out.put("phoneReference", c.phoneReference);
    out.put("createdAt", c.createdAt);
    out.put("updatedAt", c.updatedAt);
    out.put("version", c.version);
    out.put("audioRetained", c.audio != null);
    out.put(
        "corrections",
        corrections.findByCaseIdOrderByCreatedAtAsc(c.id).stream()
            .map(
                r ->
                    Map.of(
                        "id",
                        r.id,
                        "reviewer",
                        r.reviewer,
                        "transcript",
                        r.transcript,
                        "fields",
                        decode(r.fields),
                        "createdAt",
                        r.createdAt))
            .toList());
    return out;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(String owner, String search) {
    return cases.findByOwnerOrderByCreatedAtDesc(owner).stream()
        .filter(
            c -> c.transcript.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
        .map(this::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(UUID id, String owner) {
    return view(owned(id, owner));
  }

  @Transactional
  public Map<String, Object> create(String owner, JsonNode prediction, byte[] audio) {
    return persist(owner, prediction, audio, "STAFF", null);
  }

  @Transactional
  public Map<String, Object> createPublic(
      String owner, JsonNode prediction, byte[] audio, String language) {
    return persist(owner, prediction, audio, "PUBLIC", language);
  }

  private Map<String, Object> persist(
      String owner, JsonNode prediction, byte[] audio, String source, String language) {
    return persistRecord(owner, prediction, audio, source, language, null, null, null);
  }

  @Transactional
  public Map<String, Object> createPhone(
      String owner,
      JsonNode prediction,
      byte[] audio,
      String language,
      Instant consent,
      UUID intakeId,
      String reference) {
    return persistRecord(owner, prediction, audio, "PHONE", language, consent, intakeId, reference);
  }

  private Map<String, Object> persistRecord(
      String owner,
      JsonNode prediction,
      byte[] audio,
      String source,
      String language,
      Instant consent,
      UUID intakeId,
      String reference) {
    LabCase c = new LabCase();
    c.id = UUID.randomUUID();
    c.owner = owner;
    c.transcript = prediction.path("transcript").asText();
    c.prediction = prediction.toString();
    c.status = "NEEDS_REVIEW";
    c.source = source;
    c.createdAt = c.updatedAt = Instant.now();
    if (source.equals("PUBLIC") || source.equals("PHONE")) {
      c.consentedAt = consent == null ? c.createdAt : consent;
      c.submittedLanguage = language;
    }
    c.phoneIntakeId = intakeId;
    c.phoneReference = reference;
    c.audio = retain ? audio : null;
    cases.saveAndFlush(c);
    audit(
        c.id,
        source.equals("PHONE")
            ? "phone-intake"
            : source.equals("PUBLIC") ? "anonymous-intake" : owner,
        source.equals("PHONE")
            ? "PHONE_SUBMITTED"
            : source.equals("PUBLIC") ? "PUBLIC_SUBMITTED" : "CREATED");
    return view(c);
  }

  @Transactional
  public Map<String, Object> createText(String owner, Create request) {
    return create(owner, ml.extract(request.transcript()), null);
  }

  @Transactional
  public Map<String, Object> review(UUID id, String owner, Review body) {
    validator.validate(body.fields());
    LabCase c = owned(id, owner);
    if (c.version != body.version())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case changed; reload before saving");
    Correction r = new Correction();
    r.id = UUID.randomUUID();
    r.caseId = id;
    r.reviewer = owner;
    r.transcript = body.transcript();
    r.createdAt = Instant.now();
    try {
      r.fields = json.writeValueAsString(body.fields());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid fields");
    }
    corrections.save(r);
    c.status = "REVIEWED";
    c.updatedAt = Instant.now();
    cases.saveAndFlush(c);
    audit(id, owner, "REVIEW_SAVED");
    return view(c);
  }

  @Transactional
  public void delete(UUID id, String owner) {
    LabCase c = owned(id, owner);
    cases.delete(c);
    cases.flush();
    audit(id, owner, "DELETED");
  }

  private void audit(UUID id, String owner, String action) {
    AuditEvent a = new AuditEvent();
    a.id = UUID.randomUUID();
    a.caseId = id;
    a.actor = owner;
    a.action = action;
    a.createdAt = Instant.now();
    audits.save(a);
    org.slf4j.LoggerFactory.getLogger(CaseService.class).info("event={} caseId={}", action, id);
  }
}
