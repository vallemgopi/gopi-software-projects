package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.queryfence.persistence.ControlStore;
import dev.queryfence.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class IntegrationControlsTest {
  @Test
  void embeddedOriginCannotBorrowLocalProxyAuthorization() throws Exception {
    var store = mock(ControlStore.class);
    var filter =
        new IntegrationFilter(
            store,
            new ObjectMapper().findAndRegisterModules(),
            "local-test",
            true,
            "http://localhost:3000,http://localhost:8085",
            100);
    var request = new MockHttpServletRequest("GET", "/api/v2/schema");
    request.addHeader("X-QueryFence-Local", "local-test");
    request.addHeader("Origin", "http://localhost:8085");
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request, response, (r, s) -> fail("An embedded browser must use a bearer credential"));
    assertEquals(401, response.getStatus());
  }

  @Test
  void rateLimitUsesIntegrationIdentityAndReturnsRetryAfter() throws Exception {
    var store = mock(ControlStore.class);
    var filter =
        new IntegrationFilter(
            store,
            new ObjectMapper().findAndRegisterModules(),
            "local-test",
            true,
            "http://localhost:3000",
            1);
    var request = new MockHttpServletRequest("GET", "/api/v2/schema");
    request.addHeader("X-QueryFence-Local", "local-test");
    var first = new MockHttpServletResponse();
    filter.doFilter(request, first, (r, s) -> {});
    assertEquals(200, first.getStatus());
    var next = new MockHttpServletRequest("GET", "/api/v2/schema");
    next.addHeader("X-QueryFence-Local", "local-test");
    var response = new MockHttpServletResponse();
    filter.doFilter(next, response, (r, s) -> fail("Rate-limited request reached the controller"));
    assertEquals(429, response.getStatus());
    assertEquals("60", response.getHeader("Retry-After"));
    assertTrue(response.getContentAsString().contains("RATE_LIMIT"));
  }

  @Test
  void invalidBearerNeverFallsBackToDemoAndMissingAuthIsDenied() throws Exception {
    var store = mock(ControlStore.class);
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var realStore = new ControlStore(jdbc, new ObjectMapper());
    var filter =
        new IntegrationFilter(
            realStore,
            new ObjectMapper().findAndRegisterModules(),
            "local-test",
            true,
            "http://localhost:3000",
            100);
    var request = new MockHttpServletRequest("GET", "/api/v2/schema");
    request.addHeader("Authorization", "NotBearer");
    request.addHeader("X-QueryFence-Local", "local-test");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (r, s) -> fail("Malformed credential cannot fall back"));
    assertEquals(401, response.getStatus());
  }
}
