package lab;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import lab.service.MlClient;
import org.junit.jupiter.api.Test;

class MlClientTest {
  @Test
  void extractionUsesJsonEvenWhenXmlConverterIsPresent() throws Exception {
    var contentType = new AtomicReference<String>();
    var body = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/extract",
        exchange -> {
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = "{\"transcript\":\"Synthetic text\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var client = new MlClient("http://127.0.0.1:" + server.getAddress().getPort());
      assertEquals("Synthetic text", client.extract("Synthetic text").path("transcript").asText());
      assertTrue(contentType.get().startsWith("application/json"));
      assertEquals("{\"text\":\"Synthetic text\"}", body.get());
    } finally {
      server.stop(0);
    }
  }
}
