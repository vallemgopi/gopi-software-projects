package lab.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import lab.dto.Requests.*;
import lab.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cases")
public class CaseController {
  private final CaseService cases;
  private final MlClient ml;

  public CaseController(CaseService cases, MlClient ml) {
    this.cases = cases;
    this.ml = ml;
  }

  @GetMapping
  public Object list(Principal p, @RequestParam(defaultValue = "") String search) {
    return cases.list(p.getName(), search);
  }

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Object create(Principal p, @Valid @RequestBody Create body) {
    return cases.createText(p.getName(), body);
  }

  @GetMapping("/{id}")
  public Object get(Principal p, @PathVariable UUID id) {
    return cases.get(id, p.getName());
  }

  @PutMapping("/{id}")
  public Object update(Principal p, @PathVariable UUID id, @Valid @RequestBody Review body) {
    return cases.review(id, p.getName(), body);
  }

  @PostMapping("/{id}/review")
  public Object review(Principal p, @PathVariable UUID id, @Valid @RequestBody Review body) {
    return cases.review(id, p.getName(), body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(Principal p, @PathVariable UUID id) {
    cases.delete(id, p.getName());
  }

  @PostMapping(value = "/audio", consumes = "multipart/form-data")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Object audio(
      Principal p,
      @RequestParam MultipartFile file,
      @RequestParam(defaultValue = "sample") String provider,
      @RequestParam(required = false) String sampleText,
      @RequestParam(defaultValue = "auto") String language)
      throws java.io.IOException {
    byte[] b = file.getBytes();
    String n = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
    if (b.length > 5 * 1024 * 1024)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "Maximum 5 MiB");
    if (!n.endsWith(".wav")
        || b.length < 12
        || !new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        || !new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE"))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "Only PCM WAV files accepted");
    return cases.create(p.getName(), ml.transcribe(b, provider, sampleText, language), b);
  }
}
