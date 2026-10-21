package lab.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reject cross-site browser mutations; signed provider/server clients have no Origin. */
public class BrowserOriginFilter extends OncePerRequestFilter {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private final String allowedOrigin;

  public BrowserOriginFilter(String allowedOrigin) {
    this.allowedOrigin = allowedOrigin;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    if (!SAFE.contains(request.getMethod()) && origin != null && !allowedOrigin.equals(origin)) {
      response.setStatus(403);
      response.setContentType("application/json");
      response.getWriter().write("{\"message\":\"Browser origin not allowed\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
