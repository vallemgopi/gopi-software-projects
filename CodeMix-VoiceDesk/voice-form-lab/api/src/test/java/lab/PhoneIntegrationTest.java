package lab;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import lab.phone.*;
import lab.repository.CaseRepository;
import lab.service.MlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PhoneIntegrationTest {
  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
    r.add("lab.demo-password", () -> "integration-only-password");
    r.add("lab.phone.enabled", () -> true);
    r.add("lab.phone.account-sid", () -> PhoneSecurityTest.ACCOUNT);
    r.add("lab.phone.auth-token", () -> PhoneSecurityTest.TOKEN);
    r.add("lab.phone.api-key-sid", () -> "SK" + "b".repeat(32));
    r.add("lab.phone.api-key-secret", () -> "synthetic-test-secret");
    r.add("lab.phone.public-base-url", () -> PhoneSecurityTest.ORIGIN);
    r.add("lab.phone.public-number", () -> "+15550000001");
    r.add("lab.phone.poll-ms", () -> 3600000);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PhoneRepository phones;
  @Autowired CaseRepository cases;
  @Autowired PhoneWorker worker;
  @Autowired PhoneStore store;
  @MockitoBean MlClient ml;
  @MockitoBean RecordingMedia media;
  private static final AtomicInteger sequence = new AtomicInteger();

  private String sid(String prefix) {
    return prefix + String.format("%032x", sequence.incrementAndGet());
  }

  private Map<String, String> form(String call) {
    return new HashMap<>(Map.of("AccountSid", PhoneSecurityTest.ACCOUNT, "CallSid", call));
  }

  private ResultActions send(String step, Map<String, String> form) throws Exception {
    String path = "/api/phone/twilio/" + step;
    MockHttpServletRequestBuilder request =
        post(path).contentType("application/x-www-form-urlencoded");
    form.forEach(request::param);
    request.header(
        "X-Twilio-Signature", PhoneSecurityTest.sign(PhoneSecurityTest.ORIGIN + path, form));
    return mvc.perform(request);
  }

  private PhoneIntake phone(String call) {
    return phones.findAll().stream().filter(p -> p.callSid.equals(call)).findFirst().orElseThrow();
  }

  private void start(String call) throws Exception {
    var f = form(call);
    f.put("To", "+15550000001");
    f.put("From", "+15550000000");
    send("voice", f)
        .andExpect(status().isOk())
        .andExpect(xpath("/Response/Gather").exists())
        .andExpect(xpath("/Response/Record").doesNotExist());
  }

  private void select(String call, String digit) throws Exception {
    var f = form(call);
    f.put("Digits", digit);
    send("language", f)
        .andExpect(status().isOk())
        .andExpect(xpath("/Response/Gather").exists())
        .andExpect(xpath("/Response/Record").doesNotExist());
  }

  private void consent(String call) throws Exception {
    var f = form(call);
    f.put("Digits", "1");
    send("consent", f)
        .andExpect(status().isOk())
        .andExpect(xpath("/Response/Record/@maxLength").string("60"));
  }

  private void event(String call, String recording) throws Exception {
    var f = form(call);
    f.put("RecordingSid", recording);
    f.put("RecordingStatus", "completed");
    f.put("RecordingDuration", "4");
    f.put("RecordingSource", "RecordVerb");
    f.put(
        "RecordingUrl",
        "http://127.0.0.1/do-not-fetch-this"); // signed but ignored; only the validated SID reaches
    // the media provider.
    send("recording-status", f).andExpect(status().isNoContent());
  }

  @ParameterizedTest
  @CsvSource({"1,ta", "2,te", "3,kn", "4,ml", "5,en", "6,hi", "7,auto"})
  void consentedRecordingCreatesExactlyOnePrivateReviewCase(String digit, String language)
      throws Exception {
    String call = sid("CA"), recording = sid("RE");
    start(call);
    select(call, digit);
    consent(call);
    event(call, recording);
    event(call, recording);
    assertEquals("QUEUED", phone(call).state);
    verifyNoInteractions(media, ml);
    byte[] audio = "RIFF0000WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    when(media.download(recording)).thenReturn(audio);
    when(ml.transcribe(any(byte[].class), eq("whisper"), isNull(), eq(language)))
        .thenReturn(
            json.readTree(
                "{\"transcript\":\"Synthetic phone water leak at Demo"
                    + " Lane.\",\"provider\":\"synthetic-test-model\",\"fields\":{}}"));
    worker.processOne();
    PhoneIntake p = phone(call);
    assertEquals("COMPLETED", p.state);
    assertNotNull(p.caseId);
    assertNotNull(p.consentedAt);
    var saved = cases.findById(p.caseId).orElseThrow();
    assertEquals("PHONE", saved.source);
    assertEquals("NEEDS_REVIEW", saved.status);
    assertEquals(language, saved.submittedLanguage);
    assertEquals(p.reference, saved.phoneReference);
    assertNull(saved.audio);
    mvc.perform(get("/api/cases/" + p.caseId)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/phone/intakes")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/phone/intakes").with(httpBasic("reviewer", "integration-only-password")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].callSid").doesNotExist())
        .andExpect(jsonPath("$[0].recordingSid").doesNotExist());
    event(call, recording);
    worker.processOne();
    verify(media, times(1)).download(recording);
    worker.cleanup();
    verify(media).delete(recording);
    assertTrue(phone(call).mediaDeleted);
    cases.deleteById(p.caseId);
    event(call, recording);
    worker.processOne();
    assertEquals("COMPLETED", phone(call).state);
    assertNull(phone(call).caseId);
  }

  @Test
  void declinedConsentNeverRecordsOrCreatesACase() throws Exception {
    String call = sid("CA");
    start(call);
    select(call, "1");
    var f = form(call);
    f.put("Digits", "9");
    send("consent", f)
        .andExpect(status().isOk())
        .andExpect(xpath("/Response/Hangup").exists())
        .andExpect(xpath("/Response/Record").doesNotExist());
    assertEquals("DECLINED", phone(call).state);
    verifyNoInteractions(media, ml);
    f = form(call);
    f.put("RecordingSid", sid("RE"));
    f.put("RecordingStatus", "completed");
    f.put("RecordingDuration", "4");
    send("recording-status", f).andExpect(status().isConflict());
  }

  @Test
  void forgedCallbacksCannotCreateRows() throws Exception {
    long before = phones.count();
    mvc.perform(
            post("/api/phone/twilio/voice")
                .contentType("application/x-www-form-urlencoded")
                .param("CallSid", sid("CA"))
                .param("AccountSid", PhoneSecurityTest.ACCOUNT)
                .header("X-Twilio-Signature", "forged"))
        .andExpect(status().isForbidden());
    assertEquals(before, phones.count());
    verifyNoInteractions(media, ml);
  }

  @Test
  void retriesAndInterruptedJobsRecoverWithoutDuplicateCase() throws Exception {
    String call = sid("CA"), recording = sid("RE");
    start(call);
    select(call, "5");
    consent(call);
    event(call, recording);
    when(media.download(recording)).thenThrow(new java.io.IOException("synthetic outage"));
    worker.processOne();
    PhoneIntake p = phone(call);
    assertEquals("QUEUED", p.state);
    assertEquals(1, p.attempts);
    assertNull(p.caseId);
    p.state = "PROCESSING";
    p.leaseUntil = Instant.now().minusSeconds(1);
    phones.saveAndFlush(p);
    store.recover();
    assertEquals("QUEUED", phone(call).state);
    p = phone(call);
    p.nextAttemptAt = Instant.EPOCH;
    phones.saveAndFlush(p);
    doReturn("RIFF0000WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        .when(media)
        .download(recording);
    when(ml.transcribe(any(byte[].class), eq("whisper"), isNull(), eq("en")))
        .thenReturn(
            json.readTree("{\"transcript\":\"Synthetic recovered phone case\",\"fields\":{}}"));
    worker.processOne();
    assertEquals("COMPLETED", phone(call).state);
    assertEquals(2, phone(call).attempts);
    worker.cleanup();
    cases.deleteById(phone(call).caseId);
  }
}
