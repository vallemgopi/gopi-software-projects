package lab;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lab.phone.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PhoneSecurityTest {
  static final String ACCOUNT = "AC" + "a".repeat(32),
      TOKEN = "synthetic-phone-test-secret",
      ORIGIN = "https://voice.example.invalid";

  static PhoneSettings settings() {
    var s = new PhoneSettings();
    s.setEnabled(true);
    s.setPublicBaseUrl(ORIGIN);
    s.setAccountSid(ACCOUNT);
    s.setAuthToken(TOKEN);
    s.setApiKeySid("SK" + "b".repeat(32));
    s.setApiKeySecret("synthetic-media-test-secret");
    s.setPublicNumber("+15550000001");
    return s;
  }

  static String sign(String url, Map<String, String> fields) throws Exception {
    StringBuilder body = new StringBuilder(url);
    new TreeMap<>(fields).forEach((k, v) -> body.append(k).append(v));
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    return Base64.getEncoder()
        .encodeToString(mac.doFinal(body.toString().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void genuineSignaturesUseCanonicalOriginRejectTamperingAndDuplicateFields() throws Exception {
    var settings = settings();
    settings.validate();
    var verifier = new PhoneWebhookVerifier(settings);
    String path = "/api/phone/twilio/consent";
    var fields = Map.of("AccountSid", ACCOUNT, "CallSid", "CA" + "1".repeat(32), "Digits", "1");
    var request = new MockHttpServletRequest("POST", path);
    fields.forEach(request::setParameter);
    request.addHeader("X-Twilio-Signature", sign(ORIGIN + path, fields));
    request.addHeader("X-Forwarded-Host", "untrusted.example.invalid");
    assertEquals("1", verifier.verify(request).get("Digits"));
    request.setParameter("Digits", "9");
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> verifier.verify(request));
    request.setParameter("Digits", new String[] {"1", "9"});
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> verifier.verify(request));
  }

  @Test
  void disabledPhoneAndUnsafeConfigurationFailClosed() {
    var disabled = new PhoneSettings();
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> new PhoneWebhookVerifier(disabled).verify(new MockHttpServletRequest()));
    var s = settings();
    s.setPublicBaseUrl("http://localhost:8080");
    assertThrows(IllegalStateException.class, s::validate);
    s = settings();
    s.setPublicBaseUrl(ORIGIN + "/prefix");
    assertThrows(IllegalStateException.class, s::validate);
  }

  @Test
  void recordingUrlIsConstructedFromValidatedIdentifiersOnly() {
    var media = new TwilioRecordingMedia(settings());
    String sid = "RE" + "c".repeat(32);
    assertEquals(
        "https://api.twilio.com/2010-04-01/Accounts/" + ACCOUNT + "/Recordings/" + sid + ".wav",
        media.mediaUri(sid, ".wav").toString());
    assertThrows(
        IllegalArgumentException.class, () -> media.mediaUri("http://127.0.0.1/secret", ".wav"));
    assertThrows(
        IllegalArgumentException.class, () -> media.mediaUri(sid + "/../../private", ".wav"));
  }
}
