package lab.phone;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

@RestController
public class PhoneController {
  private final PhoneWebhookVerifier verifier;
  private final PhoneStore store;
  private final PhoneSettings settings;
  private final boolean retain;

  public PhoneController(
      PhoneWebhookVerifier verifier,
      PhoneStore store,
      PhoneSettings settings,
      @org.springframework.beans.factory.annotation.Value("${lab.retain-audio}") boolean retain) {
    this.verifier = verifier;
    this.store = store;
    this.settings = settings;
    this.retain = retain;
  }

  private String e(String text) {
    return HtmlUtils.htmlEscape(text);
  }

  private String url(String step) {
    return e(settings.getPublicBaseUrl() + "/api/phone/twilio/" + step);
  }

  private String say(String text) {
    return "<Say voice=\"alice\" language=\"en-US\">" + e(text) + "</Say>";
  }

  private String prompt(String filename, String fallback) {
    return settings.getPromptsBaseUrl().isBlank()
        ? say(fallback)
        : "<Play>" + e(settings.getPromptsBaseUrl() + "/" + filename + ".wav") + "</Play>";
  }

  private String xml(String body) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>" + body + "</Response>";
  }

  private String end(String text) {
    return xml(say(text) + "<Hangup/>");
  }

  private String render(PhoneIntake call) {
    if (call.state.equals("AWAITING_LANGUAGE"))
      return xml(
          "<Gather input=\"dtmf\" numDigits=\"1\" timeout=\"10\" actionOnEmptyResult=\"true\""
              + " method=\"POST\" action=\""
              + url("language")
              + "\">"
              + prompt(
                  "menu",
                  "Welcome to the complaint review desk. This is not an emergency service. For"
                      + " Tamil press 1. Telugu 2. Kannada 3. Malayalam 4. English 5. Hindi 6."
                      + " Automatic language selection 7.")
              + "</Gather>");
    if (call.state.equals("AWAITING_CONSENT"))
      return xml(
          "<Gather input=\"dtmf\" numDigits=\"1\" timeout=\"10\" actionOnEmptyResult=\"true\""
              + " method=\"POST\" action=\""
              + url("consent")
              + "\">"
              + prompt(
                  "consent-" + call.language,
                  "Your message will be recorded by the telephone provider and processed for staff"
                      + " review. The provider recording is requested for deletion after"
                      + " processing. Text and review details are saved by this desk. "
                      + (retain
                          ? "Original audio is also retained by this desk. "
                          : "Original audio is not retained by this desk after processing. ")
                      + "Avoid sensitive identifiers. This does not contact an authority or"
                      + " dispatch help. Press 1 to agree to recording and processing, or 9 to"
                      + " decline.")
              + "</Gather>");
    if (call.state.equals("AWAITING_RECORDING"))
      return xml(
          prompt(
                  "record-" + call.language,
                  "After the beep, describe the problem and location in your selected language,"
                      + " with English mixing if needed. Speak for up to sixty seconds. Press the"
                      + " pound key when finished. A person will review the result.")
              + "<Record maxLength=\"60\" timeout=\"5\" finishOnKey=\"#\" playBeep=\"true\""
              + " transcribe=\"false\" action=\""
              + url("recorded")
              + "\" method=\"POST\" recordingStatusCallback=\""
              + url("recording-status")
              + "\" recordingStatusCallbackMethod=\"POST\" recordingStatusCallbackEvent=\"completed"
              + " absent\"/>");
    if (call.state.equals("DECLINED")) return end("No complaint recording was requested. Goodbye.");
    if (call.state.equals("FAILED") || call.state.equals("EXPIRED"))
      return end(
          "We could not complete this intake. Please use the web complaint form or contact staff."
              + " Goodbye.");
    return end(
        "Your call reference is "
            + String.join(", ", call.reference.split(""))
            + ". Your recording still needs processing and staff review. Keep this reference for"
            + " contacting the desk. Goodbye.");
  }

  @PostMapping(
      value = "/api/phone/twilio/voice",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_XML_VALUE)
  public String voice(HttpServletRequest request) {
    var p = verifier.verify(request);
    if (!settings.getPublicNumber().equals(p.get("To")))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "This telephone number is not configured for intake.");
    return render(store.start(p.get("CallSid")));
  }

  @PostMapping(
      value = "/api/phone/twilio/language",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_XML_VALUE)
  public String language(HttpServletRequest request) {
    var p = verifier.verify(request);
    return render(store.language(p.get("CallSid"), p.getOrDefault("Digits", "")));
  }

  @PostMapping(
      value = "/api/phone/twilio/consent",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_XML_VALUE)
  public String consent(HttpServletRequest request) {
    var p = verifier.verify(request);
    return render(store.consent(p.get("CallSid"), p.getOrDefault("Digits", "")));
  }

  @PostMapping(
      value = "/api/phone/twilio/recorded",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_XML_VALUE)
  public String recorded(HttpServletRequest request) {
    var p = verifier.verify(request);
    var call = store.current(p.get("CallSid"));
    if (call.consentedAt == null) return render(call);
    return end(
        "Your call reference is "
            + String.join(", ", call.reference.split(""))
            + ". Recording has ended. The message still needs processing and staff review. If you"
            + " need follow up, give this reference to staff. Goodbye.");
  }

  @PostMapping(
      value = "/api/phone/twilio/recording-status",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<Void> callback(HttpServletRequest request) {
    var p = verifier.verify(request);
    String sid = p.getOrDefault("RecordingSid", "");
    String status = p.getOrDefault("RecordingStatus", "");
    if (!sid.matches("RE[0-9a-fA-F]{32}")
        || !Set.of("completed", "absent", "failed").contains(status)
        || !p.getOrDefault("RecordingSource", "RecordVerb").equals("RecordVerb"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recording event.");
    int seconds = 0;
    try {
      seconds = Integer.parseInt(p.getOrDefault("RecordingDuration", "0"));
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recording duration.");
    }
    store.recording(p.get("CallSid"), sid, status, seconds);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/phone/status")
  public Object status() {
    return Map.of(
        "enabled",
        settings.isEnabled(),
        "publicNumber",
        settings.isEnabled() ? settings.getPublicNumber() : "",
        "provider",
        "Twilio inbound recording",
        "localizedPromptsConfigured",
        !settings.getPromptsBaseUrl().isBlank(),
        "notice",
        "A live number, provider credentials and HTTPS webhooks are required. Configuration alone"
            + " does not verify a real phone call.");
  }

  @GetMapping("/api/phone/intakes")
  public Object list(Principal p) {
    return store.list(p.getName());
  }
}
