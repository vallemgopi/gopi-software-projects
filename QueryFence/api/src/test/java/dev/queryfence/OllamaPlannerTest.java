package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.queryfence.planner.OllamaPlanner;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.*;

class OllamaPlannerTest {
  HttpServer server;
  String response;
  int status = 200;
  final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        e -> {
          String request = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(request.contains("never SQL"));
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(status, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  OllamaPlanner planner() {
    return new OllamaPlanner(
        "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", mapper);
  }

  @Test
  void parsesStructuredResponse() throws Exception {
    response =
        mapper.writeValueAsString(
            Map.of(
                "message",
                Map.of(
                    "content",
                    "{\"metric\":\"ORDER_COUNT\",\"dimension\":\"MONTH\",\"timeWindow\":\"ALL_TIME\",\"category\":null,\"region\":null,\"limit\":20,\"explanation\":\"Count"
                        + " orders\"}")));
    assertEquals(20, planner().propose("order count by month").limit());
  }

  @Test
  void rejectsMalformedUnsupportedAndProviderErrors() throws Exception {
    for (String body :
        new String[] {"not JSON", "{\"unsupported\":true}", "{\"sql\":\"SELECT 1\"}"}) {
      response = mapper.writeValueAsString(Map.of("message", Map.of("content", body)));
      assertThrows(OllamaPlanner.ProviderException.class, () -> planner().propose("test"));
    }
    status = 503;
    response = "unavailable";
    assertThrows(OllamaPlanner.ProviderException.class, () -> planner().propose("test"));
  }
}
