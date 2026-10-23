package dev.queryfence;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.queryfence.api.*;
import dev.queryfence.planner.*;
import dev.queryfence.workflow.AnalyticsWorkflow;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiTest {
  AnalyticsWorkflow service = mock(AnalyticsWorkflow.class);
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkflowController(
                    service,
                    new dev.queryfence.catalog.BusinessCatalog(),
                    new dev.queryfence.security.RolePolicy(),
                    mock(dev.queryfence.persistence.ControlStore.class),
                    new DemoPlanner()))
            .setControllerAdvice(new ApiErrors())
            .setMessageConverters(
                new MappingJackson2HttpMessageConverter(
                    new ObjectMapper()
                        .findAndRegisterModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
            .build();
  }

  @Test
  void validatesRequestsAndConfirmation() throws Exception {
    for (String body :
        new String[] {
          "{}",
          "{\"question\":\"\"}",
          "{\"question\":\"" + "x".repeat(501) + "\"}",
          "{\"question\":\"hello\",\"sql\":\"select 1\"}"
        })
      mvc.perform(
              post("/api/v1/queries/plan").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
          .andExpect(jsonPath("$.timestamp").exists());
    mvc.perform(
            post("/api/v1/queries/00000000-0000-0000-0000-000000000000/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmed\":false}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
