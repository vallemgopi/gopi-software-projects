package lab;

import static org.junit.jupiter.api.Assertions.*;

import lab.config.BrowserOriginFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class BrowserOriginFilterTest {
  @Test
  void rejectsForeignAndNullOriginsWithoutRunningController() throws Exception {
    for (String origin : new String[] {"https://attacker.example", "null"}) {
      var request = new MockHttpServletRequest("POST", "/api/cases");
      request.addHeader("Origin", origin);
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      new BrowserOriginFilter("http://localhost:5173").doFilter(request, response, chain);
      assertEquals(403, response.getStatus());
      assertNull(chain.getRequest());
    }
  }

  @Test
  void allowsConfiguredBrowserAndNonBrowserClients() throws Exception {
    for (String origin : new String[] {"http://localhost:5173", null}) {
      var request = new MockHttpServletRequest("POST", "/api/cases");
      if (origin != null) request.addHeader("Origin", origin);
      var chain = new MockFilterChain();
      new BrowserOriginFilter("http://localhost:5173")
          .doFilter(request, new MockHttpServletResponse(), chain);
      assertNotNull(chain.getRequest());
    }
  }
}
