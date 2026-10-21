package lab;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import lab.service.MlClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApiIntegrationTest {
  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
    r.add("lab.demo-password", () -> "integration-only-password");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean MlClient ml;

  @Test
  void publicSubmissionRequiresConsentReturnsReceiptAndCannotBeReadAnonymously() throws Exception {
    mvc.perform(get("/api/public/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.audioRetained").value(false));
    mvc.perform(
            post("/api/public/submissions")
                .contentType("application/json")
                .content("{\"transcript\":\"Water leak\",\"language\":\"ta\",\"consent\":false}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/public/submissions")
                .contentType("application/json")
                .content(
                    "{\"transcript\":\"Water"
                        + " leak\",\"language\":\"ta\",\"consent\":true,\"website\":\"spam\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(ml);
    when(ml.extract("Synthetic public water leak."))
        .thenReturn(
            json.readTree(
                "{\"transcript\":\"Synthetic public water"
                    + " leak.\",\"provider\":\"test\",\"fields\":{}}"));
    String response =
        mvc.perform(
                post("/api/public/submissions")
                    .contentType("application/json")
                    .content(
                        "{\"transcript\":\"Synthetic public water"
                            + " leak.\",\"language\":\"ta\",\"consent\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("AWAITING_REVIEW"))
            .andExpect(jsonPath("$.prediction").doesNotExist())
            .andExpect(jsonPath("$.transcript").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(response).path("reference").asText();
    mvc.perform(get("/api/cases/" + id)).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/cases/" + id)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/public/submissions/" + id)).andExpect(status().isUnauthorized());
    var auth = httpBasic("reviewer", "integration-only-password");
    mvc.perform(get("/api/cases/" + id).with(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("PUBLIC"))
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.submittedLanguage").value("ta"))
        .andExpect(jsonPath("$.consentedAt").isNotEmpty());
    mvc.perform(delete("/api/cases/" + id).with(auth)).andExpect(status().isNoContent());
  }

  @Test
  void publicAudioAlwaysUsesRealRecognitionAndRejectsInvalidFiles() throws Exception {
    var invalid =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "bad.wav", "audio/wav", new byte[] {1, 2, 3});
    mvc.perform(
            multipart("/api/public/submissions/audio")
                .file(invalid)
                .param("language", "ta")
                .param("consent", "true"))
        .andExpect(status().isUnsupportedMediaType());
    byte[] bytes = "RIFF0000WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    when(ml.transcribe(any(byte[].class), eq("whisper"), isNull(), eq("ta")))
        .thenReturn(json.readTree("{\"transcript\":\"Synthetic audio test\",\"fields\":{}}"));
    var valid =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "synthetic.wav", "audio/wav", bytes);
    String response =
        mvc.perform(
                multipart("/api/public/submissions/audio")
                    .file(valid)
                    .param("language", "ta")
                    .param("consent", "true")
                    .param("provider", "sample"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    verify(ml).transcribe(any(byte[].class), eq("whisper"), isNull(), eq("ta"));
    String id = json.readTree(response).path("reference").asText();
    mvc.perform(delete("/api/cases/" + id).with(httpBasic("reviewer", "integration-only-password")))
        .andExpect(status().isNoContent());
  }

  @Test
  void authCreateReviewExportDelete() throws Exception {
    mvc.perform(get("/api/cases")).andExpect(status().isUnauthorized());
    when(ml.extract("Water leak at Demo Lane."))
        .thenReturn(
            json.readTree(
                "{\"transcript\":\"Water leak at Demo"
                    + " Lane.\",\"provider\":\"test\",\"fields\":{\"location\":{\"value\":\"Demo"
                    + " Lane\"}}}"));
    var auth = httpBasic("reviewer", "integration-only-password");
    String result =
        mvc.perform(
                post("/api/cases")
                    .with(auth)
                    .contentType("application/json")
                    .content("{\"transcript\":\"Water leak at Demo Lane.\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(result).path("id").asText();
    String review =
        "{\"transcript\":\"Water leak at Corrected"
            + " Lane.\",\"version\":0,\"fields\":{\"category\":\"water\",\"summary\":\"Water"
            + " leak\",\"location\":\"Corrected Lane\",\"datetime\":\"\",\"identifier\":\"\"}}";
    mvc.perform(
            post("/api/cases/" + id + "/review")
                .with(auth)
                .contentType("application/json")
                .content(review))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prediction.fields.location.value").value("Demo Lane"))
        .andExpect(jsonPath("$.corrections[0].fields.location").value("Corrected Lane"));
    mvc.perform(
            post("/api/cases/" + id + "/review")
                .with(auth)
                .contentType("application/json")
                .content(review))
        .andExpect(status().isConflict());
    mvc.perform(get("/api/exports/corrections").with(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cases[0].corrections[0].fields.location").value("Corrected Lane"));
    mvc.perform(delete("/api/cases/" + id).with(auth)).andExpect(status().isNoContent());
    mvc.perform(get("/api/cases/" + id).with(auth)).andExpect(status().isNotFound());
  }
}
