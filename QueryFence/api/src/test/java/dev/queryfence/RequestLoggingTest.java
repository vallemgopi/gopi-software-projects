package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.queryfence.api.RequestLogging;
import org.junit.jupiter.api.Test;
import org.slf4j.*;
import org.springframework.mock.web.*;

class RequestLoggingTest {
  @Test
  void correlationIdAppearsOnceAndMdcIsCleared() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestLogging.class);
    var appender =
        new ListAppender<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    appender.start();
    logger.addAppender(appender);
    try {
      var request = new MockHttpServletRequest("POST", "/api/v1/queries/plan");
      var response = new MockHttpServletResponse();
      new RequestLogging()
          .doFilter(request, response, (req, res) -> assertNotNull(MDC.get("requestId")));
      var event = appender.list.getFirst();
      assertEquals("http_request", event.getMessage());
      assertEquals(response.getHeader("X-Request-ID"), event.getMDCPropertyMap().get("requestId"));
      // ECS combines MDC and fluent key/value fields; duplicate names break JSON encoding.
      assertTrue(event.getKeyValuePairs().stream().noneMatch(pair -> pair.key.equals("requestId")));
      assertNull(MDC.get("requestId"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
