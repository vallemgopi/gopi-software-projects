package lab.controller;

import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lab.dto.Requests.PublicText;
import lab.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public")
public class PublicIntakeController {
  private final CaseService cases;
  private final MlClient ml;
  private final PublicIntakeGuard guard;
  private final String reviewer;
  private final boolean retain;
  private final boolean enabled;
  private final lab.phone.PhoneSettings phone;

  public PublicIntakeController(
      CaseService cases,
      MlClient ml,
      PublicIntakeGuard guard,
      @Value("${lab.demo-user}") String reviewer,
      @Value("${lab.retain-audio}") boolean retain,
      @Value("${lab.public-intake:true}") boolean enabled,
      lab.phone.PhoneSettings phone) {
    this.cases = cases;
    this.ml = ml;
    this.guard = guard;
    this.reviewer = reviewer;
    this.retain = retain;
    this.enabled = enabled;
    this.phone = phone;
  }

  @GetMapping("/config")
  public Object config() {
    return Map.of(
        "enabled",
        enabled,
        "audioRetained",
        retain,
        "maxAudioBytes",
        5 * 1024 * 1024,
        "maxAudioSeconds",
        60,
        "phone",
        Map.of(
            "enabled",
            phone.isEnabled(),
            "number",
            phone.isEnabled() ? phone.getPublicNumber() : ""),
        "languages",
        Map.of(
            "ta",
            "Tamil",
            "te",
            "Telugu",
            "kn",
            "Kannada",
            "ml",
            "Malayalam",
            "en",
            "English",
            "hi",
            "Hindi",
            "auto",
            "Auto detect"));
  }

  private void check(String language, boolean consent, String website) {
    if (!enabled)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Public intake is temporarily closed.");
    if (!consent
        || website != null && !website.isEmpty()
        || !Set.of("auto", "ta", "te", "kn", "ml", "en", "hi").contains(language))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Confirm consent and choose a supported language.");
  }

  private Map<String, Object> receipt(Map<String, Object> saved) {
    // Anonymous callers get a receipt only, never a case listing, model output, or corrections.
    return Map.of(
        "reference",
        saved.get("id"),
        "receivedAt",
        saved.get("createdAt"),
        "status",
        "AWAITING_REVIEW",
        "message",
        "Received for staff review. This does not submit a complaint to an external authority.");
  }

  @PostMapping("/submissions")
  @ResponseStatus(HttpStatus.CREATED)
  public Object text(@Valid @RequestBody PublicText request) {
    check(request.language(), Boolean.TRUE.equals(request.consent()), request.website());
    guard.enter();
    try {
      return receipt(
          cases.createPublic(reviewer, ml.extract(request.transcript()), null, request.language()));
    } finally {
      guard.leave();
    }
  }

  @PostMapping(value = "/submissions/audio", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  public Object audio(
      @RequestParam MultipartFile file,
      @RequestParam(defaultValue = "auto") String language,
      @RequestParam boolean consent,
      @RequestParam(defaultValue = "") String website)
      throws java.io.IOException {
    check(language, consent, website);
    String name = file.getOriginalFilename();
    byte[] bytes = file.getBytes();
    if (bytes.length > 5 * 1024 * 1024)
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Maximum audio size is 5 MiB.");
    if (name == null
        || !name.toLowerCase(Locale.ROOT).endsWith(".wav")
        || bytes.length < 12
        || !new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        || !new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE"))
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload a PCM WAV clip, or use the record button.");
    guard.enter();
    try {
      return receipt(
          cases.createPublic(
              reviewer, ml.transcribe(bytes, "whisper", null, language), bytes, language));
    } finally {
      guard.leave();
    }
  }
}
