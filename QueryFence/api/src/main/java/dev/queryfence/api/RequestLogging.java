package dev.queryfence.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(-200)
public class RequestLogging extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestLogging.class);

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    MDC.put("requestId", id);
    response.setHeader("X-Request-ID", id);
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      log.atInfo()
          .addKeyValue("method", request.getMethod())
          .addKeyValue("status", response.getStatus())
          .addKeyValue("elapsedMs", (System.nanoTime() - start) / 1_000_000)
          .log("http_request");
      MDC.clear();
    }
  }
}
