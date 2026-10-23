package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.queryfence.persistence.ControlStore;
import dev.queryfence.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WorkflowIntegrationTest {
  @Test
  void openApiDocumentsPublicHealthAndProtectedSchema() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v2/health'].get.security").doesNotExist())
        .andExpect(
            jsonPath("$.paths['/api/v2/schema'].get.security[0].integrationBearer").exists());
  }

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17.6-alpine").withInitScript("v2-test-init.sql");

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.flyway.url", postgres::getJdbcUrl);
    r.add("spring.flyway.user", postgres::getUsername);
    r.add("spring.flyway.password", postgres::getPassword);
    r.add("ANALYTICS_PASSWORD", () -> "ephemeral-analytics");
    r.add("CONTROL_PASSWORD", () -> "ephemeral-control");
    r.add("security.local-demo-secret", () -> "test-local-secret");
    r.add("security.requests-per-minute", () -> 10000);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired ControlStore store;

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder local(
      String path, String body) {
    return post("/api/v2" + path)
        .header("X-QueryFence-Local", "test-local-secret")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  JsonNode response(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  JsonNode plan() throws Exception {
    return response(
        mvc.perform(local("/queries/plan", "{\"question\":\"Revenue by region all time\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cost.allowed").value(true))
            .andReturn());
  }

  @Test
  void persistentApprovalExecutionSummaryAndReplay() throws Exception {
    var p = plan();
    String id = p.get("planId").asText();
    mvc.perform(
            local(
                "/queries/" + id + "/execute",
                "{\"confirmed\":true,\"approvalToken\":\"invented\"}"))
        .andExpect(status().isConflict());
    mvc.perform(
            local("/queries/" + id + "/approve", "{\"confirmed\":true,\"reviewDigest\":\"stale\"}"))
        .andExpect(status().isConflict());
    var approval =
        response(
            mvc.perform(
                    local(
                        "/queries/" + id + "/approve",
                        "{\"confirmed\":true,\"reviewDigest\":\""
                            + p.get("reviewDigest").asText()
                            + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
    String token = approval.get("approvalToken").asText();
    var run = "{\"confirmed\":true,\"approvalToken\":\"" + token + "\"}";
    mvc.perform(local("/queries/" + id + "/execute", run))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(5));
    mvc.perform(local("/queries/" + id + "/execute", run)).andExpect(status().isConflict());
    mvc.perform(
            get("/api/v2/queries/" + id + "/results?summary=true")
                .header("X-QueryFence-Local", "test-local-secret"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.claims[0].row").value(1));
    var row =
        store.jdbc.queryForMap(
            "SELECT status,confirmation,approval_hash,result FROM queryfence.plans WHERE id=?",
            UUID.fromString(id));
    assertEquals("EXECUTED", row.get("status"));
    assertEquals(true, row.get("confirmation"));
    assertNull(row.get("approval_hash"));
    assertTrue(
        store.jdbc.queryForObject(
                "SELECT count(*) FROM queryfence.audit_events WHERE plan_id=? AND"
                    + " outcome='EXECUTED'",
                Integer.class,
                UUID.fromString(id))
            > 0);
    assertFalse(
        store
            .json(
                store.auditHistory(
                    new Actor(
                        "local-demo:ANALYST", RolePolicy.Role.ANALYST, Set.of(), Set.of(), true)))
            .contains(token));
  }

  @Test
  void clarificationAndEditingInvalidatePreviousPlan() throws Exception {
    var c =
        response(
            mvc.perform(local("/queries/plan", "{\"question\":\"Revenue by region\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLARIFICATION_REQUIRED"))
                .andReturn());
    var p =
        response(
            mvc.perform(
                    local(
                        "/queries/" + c.get("clarificationId").asText() + "/clarify",
                        "{\"selections\":{\"timeWindow\":\"ALL_TIME\"}}"))
                .andExpect(status().isOk())
                .andReturn());
    var edit = p.get("plan").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) edit).put("limit", 3);
    var next =
        response(
            mvc.perform(
                    patch("/api/v2/queries/" + p.get("planId").asText())
                        .header("X-QueryFence-Local", "test-local-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn());
    assertNotEquals(p.get("planId"), next.get("planId"));
    mvc.perform(
            local(
                "/queries/" + p.get("planId").asText() + "/approve",
                "{\"confirmed\":true,\"reviewDigest\":\"" + p.get("reviewDigest").asText() + "\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void backendRoleBoundaryAndExpiry() throws Exception {
    mvc.perform(
            local("/queries/plan", "{\"question\":\"Return rate by month all time\"}")
                .header("X-Demo-Role", "FINANCE"))
        .andExpect(status().isForbidden());
    mvc.perform(
            local(
                    "/queries/plan",
                    "{\"question\":\"Order count by region all time for electronics in north\"}")
                .header("X-Demo-Role", "OPERATIONS"))
        .andExpect(status().isForbidden());
    var p = plan();
    store.jdbc.update(
        "UPDATE queryfence.plans SET expires_at=now()-interval '1 second' WHERE id=?",
        UUID.fromString(p.get("planId").asText()));
    mvc.perform(
            local(
                "/queries/" + p.get("planId").asText() + "/approve",
                "{\"confirmed\":true,\"reviewDigest\":\"" + p.get("reviewDigest").asText() + "\"}"))
        .andExpect(status().isGone());
  }

  String credential(String scope, boolean expired, boolean revoked) {
    String token = Tokens.create();
    store.jdbc.update(
        "INSERT INTO"
            + " queryfence.credentials(id,name,token_hash,role,scopes,origins,expires_at,revoked)"
            + " VALUES (?,? ,?,'FINANCE',?::text[],ARRAY['http://localhost:3000'],now()+(? *"
            + " interval '1 second'),?)",
        UUID.randomUUID(),
        "test-" + UUID.randomUUID(),
        Tokens.hash(token),
        "{" + scope + "}",
        expired ? -1 : 3600,
        revoked);
    return token;
  }

  @Test
  void credentialsScopesOriginsAndBodyLimit() throws Exception {
    mvc.perform(get("/api/v2/schema")).andExpect(status().isUnauthorized());
    for (String token :
        List.of(credential("schema:read", true, false), credential("schema:read", false, true)))
      mvc.perform(get("/api/v2/schema").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized());
    var token = credential("schema:read", false, false);
    mvc.perform(get("/api/v2/schema").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("FINANCE"));
    mvc.perform(
            post("/api/v2/queries/plan")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Revenue by region all time\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v2/schema")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://unapproved.example"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v2/schema")
                .header("Authorization", "Bearer " + token)
                .header("X-QueryFence-Role", "ANALYST"))
        .andExpect(status().isForbidden());
    mvc.perform(local("/queries/plan", "x".repeat(17000))).andExpect(status().isPayloadTooLarge());
  }

  @Test
  void controlAndAnalyticsRolesHaveIndependentPermissions() throws Exception {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> store.jdbc.queryForList("SELECT * FROM analytics.orders"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> store.jdbc.update("DELETE FROM queryfence.audit_events"));
  }

  @Test
  void credentialOriginsAreNarrowerThanGlobalCorsAndUnknownValuesAreRejected() throws Exception {
    var token = credential("schema:read", false, false);
    mvc.perform(
            get("/api/v2/schema")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "http://127.0.0.1:3000"))
        .andExpect(status().isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                    "/api/v2/schema")
                .header("Origin", "http://localhost:3000"))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    mvc.perform(
            local(
                "/queries/plan",
                "{\"question\":\"Revenue by region all time\",\"sql\":\"DELETE FROM"
                    + " analytics.orders\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void changedStoredPlanCannotReusePreviouslyReviewedDigest() throws Exception {
    var p = plan();
    UUID id = UUID.fromString(p.get("planId").asText());
    store.jdbc.update(
        "UPDATE queryfence.plans SET plan=jsonb_set(plan,'{limit}','3'::jsonb) WHERE id=?", id);
    mvc.perform(
            local(
                "/queries/" + id + "/approve",
                "{\"confirmed\":true,\"reviewDigest\":\"" + p.get("reviewDigest").asText() + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STALE_REVIEW"));
  }

  @Test
  void concurrentDatabaseClaimsExecuteOnlyOnce() throws Exception {
    var p = plan();
    String id = p.get("planId").asText();
    var approved =
        response(
            mvc.perform(
                    local(
                        "/queries/" + id + "/approve",
                        "{\"confirmed\":true,\"reviewDigest\":\""
                            + p.get("reviewDigest").asText()
                            + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
    String body =
        "{\"confirmed\":true,\"approvalToken\":\"" + approved.get("approvalToken").asText() + "\"}";
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            () ->
                mvc.perform(local("/queries/" + id + "/execute", body))
                    .andReturn()
                    .getResponse()
                    .getStatus());
      int successes = 0;
      for (var result : pool.invokeAll(tasks)) {
        int status = result.get();
        assertTrue(status == 200 || status == 409);
        if (status == 200) successes++;
      }
      assertEquals(1, successes);
    }
    assertEquals(
        1,
        store.jdbc.queryForObject(
            "SELECT count(*) FROM queryfence.audit_events WHERE plan_id=? AND action='EXECUTE' AND"
                + " outcome='EXECUTED'",
            Integer.class,
            UUID.fromString(id)));
  }
}
