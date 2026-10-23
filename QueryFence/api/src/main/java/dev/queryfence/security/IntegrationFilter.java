package dev.queryfence.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.queryfence.api.ApiErrors.ErrorBody;
import dev.queryfence.persistence.ControlStore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-100)
public class IntegrationFilter extends OncePerRequestFilter {
  public static final String ACTOR = "queryfence.actor";
  public static final Set<String> DEMO_SCOPES =
      Set.of(
          "schema:read",
          "query:plan",
          "query:review",
          "query:execute",
          "query:results",
          "audit:read",
          "evaluation:read",
          "evaluation:run");
  private final ControlStore store;
  private final ObjectMapper mapper;
  private final String localSecret;
  private final boolean demoEnabled;
  private final Set<String> allowedOrigins;
  private final int limit;

  @Value(
      "${security.demo-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173}")
  private String demoOrigins =
      "http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173";

  private record Bucket(long minute, int count) {}

  private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(128, .75f, true);

  public IntegrationFilter(
      ControlStore store,
      ObjectMapper mapper,
      @Value("${security.local-demo-secret:}") String secret,
      @Value("${security.demo-enabled:true}") boolean demo,
      @Value("${security.allowed-origins:http://localhost:3000}") String origins,
      @Value("${security.requests-per-minute:120}") int limit) {
    this.store = store;
    this.mapper = mapper;
    localSecret = secret;
    demoEnabled = demo;
    allowedOrigins = Set.copyOf(Arrays.asList(origins.split(",")));
    this.limit = limit;
  }

  public static Actor actor(HttpServletRequest request) {
    var actor = (Actor) request.getAttribute(ACTOR);
    if (actor == null)
      throw new ApiProblem(401, "AUTH_REQUIRED", "A scoped integration credential is required.");
    return actor;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/") || path.endsWith("/health");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    Actor actor = new Actor("unauthenticated", RolePolicy.Role.ANALYST, Set.of(), Set.of(), false);
    try {
      String origin = request.getHeader("Origin");
      if (origin != null && !allowedOrigins.contains(origin))
        throw new ApiProblem(403, "ORIGIN_BLOCKED", "Origin is not on the server allow-list.");
      if (origin != null) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Expose-Headers", "X-Request-ID,Retry-After");
      }
      if (request.getMethod().equals("OPTIONS")) {
        response.setHeader("Access-Control-Allow-Methods", "GET,POST,PATCH,OPTIONS");
        response.setHeader(
            "Access-Control-Allow-Headers",
            "Authorization,Content-Type,X-Demo-Role,X-QueryFence-Role");
        response.setStatus(204);
        return;
      }
      rate("ip:" + request.getRemoteAddr(), limit * 4);
      String auth = request.getHeader("Authorization");
      if (auth != null) {
        if (!auth.startsWith("Bearer ") || auth.length() > 200) throw unauthorized();
        String hash = Tokens.hash(auth.substring(7));
        var rows =
            store.jdbc.queryForList(
                "SELECT id,name,role,scopes,origins FROM queryfence.credentials WHERE token_hash=?"
                    + " AND revoked=false AND expires_at>now()",
                hash);
        if (rows.isEmpty()) throw unauthorized();
        var row = rows.getFirst();
        actor =
            new Actor(
                "integration:" + (String) row.get("name"),
                RolePolicy.Role.valueOf((String) row.get("role")),
                array(row.get("scopes")),
                array(row.get("origins")),
                false);
        if (origin != null && !actor.origins().contains(origin))
          throw new ApiProblem(
              403, "ORIGIN_BLOCKED", "Credential does not permit this browser origin.");
      } else if (demoEnabled
          && !localSecret.isBlank()
          && Tokens.equal(localSecret, request.getHeader("X-QueryFence-Local"))) {
        if (origin != null && !Arrays.asList(demoOrigins.split(",")).contains(origin))
          throw new ApiProblem(
              401,
              "AUTH_REQUIRED",
              "Embedded browser integrations must supply a scoped bearer credential.");
        RolePolicy.Role role;
        try {
          role =
              RolePolicy.Role.valueOf(
                  Optional.ofNullable(request.getHeader("X-Demo-Role")).orElse("ANALYST"));
        } catch (Exception e) {
          throw new ApiProblem(400, "INVALID_ROLE", "Unknown demo role.");
        }
        actor = new Actor("local-demo:" + role, role, DEMO_SCOPES, allowedOrigins, true);
      } else throw unauthorized();
      String expectedRole = request.getHeader("X-QueryFence-Role");
      if (expectedRole != null && !expectedRole.equals(actor.role().name()))
        throw new ApiProblem(
            403, "ROLE_POLICY", "Configured role must match the authenticated credential.");
      rate("actor:" + actor.id(), limit);
      request.setAttribute(ACTOR, actor);
      byte[] body = request.getInputStream().readNBytes(16385);
      if (body.length > 16384)
        throw new ApiProblem(413, "REQUEST_TOO_LARGE", "Request body must be at most 16 KiB.");
      chain.doFilter(new BodyRequest(request, body), response);
      store.audit(
          actor,
          "REST_" + request.getMethod(),
          planId(request.getRequestURI()),
          "HTTP_" + response.getStatus(),
          false,
          Map.of(
              "integration",
              !actor.demo(),
              "requestedAction",
              requestedAction(request.getRequestURI())));
    } catch (ApiProblem problem) {
      response.setStatus(problem.status);
      if (problem.status == 429) response.setHeader("Retry-After", "60");
      response.setContentType("application/json");
      mapper.writeValue(
          response.getOutputStream(),
          new ErrorBody(Instant.now(), problem.code, problem.getMessage(), MDC.get("requestId")));
      try {
        store.audit(
            actor,
            "INTEGRATION_GUARD",
            planId(request.getRequestURI()),
            problem.code,
            false,
            Map.of("requestedAction", requestedAction(request.getRequestURI())));
      } catch (org.springframework.dao.DataAccessException unavailable) {
        /* No credential or database internals are written to the response. */
      }
    } catch (org.springframework.dao.DataAccessException unavailable) {
      if (!response.isCommitted()) {
        response.resetBuffer();
        response.setStatus(503);
        response.setContentType("application/json");
        mapper.writeValue(
            response.getOutputStream(),
            new ErrorBody(
                Instant.now(),
                "CONTROL_DATABASE_UNAVAILABLE",
                "Authentication or audit storage is unavailable. No new action is authorized.",
                MDC.get("requestId")));
      }
    }
  }

  private String requestedAction(String path) {
    String last = path.substring(path.lastIndexOf('/') + 1);
    return Set.of(
                "schema",
                "plan",
                "clarify",
                "approve",
                "execute",
                "results",
                "history",
                "audit",
                "evaluations",
                "baseline")
            .contains(last)
        ? last
        : "review-or-edit";
  }

  private ApiProblem unauthorized() {
    return new ApiProblem(
        401, "AUTH_REQUIRED", "Credential is missing, invalid, expired or revoked.");
  }

  private Set<String> array(Object value) {
    try {
      return Set.of((String[]) ((java.sql.Array) value).getArray());
    } catch (Exception e) {
      throw unauthorized();
    }
  }

  private UUID planId(String path) {
    var m = java.util.regex.Pattern.compile("/queries/([0-9a-fA-F-]{36})(?:/|$)").matcher(path);
    try {
      return m.find() ? UUID.fromString(m.group(1)) : null;
    } catch (Exception e) {
      return null;
    }
  }

  private synchronized void rate(String key, int maximum) {
    long minute = Instant.now().getEpochSecond() / 60;
    var previous = buckets.get(key);
    int count = previous == null || previous.minute() != minute ? 1 : previous.count() + 1;
    buckets.put(key, new Bucket(minute, count));
    while (buckets.size() > 10000) buckets.remove(buckets.keySet().iterator().next());
    if (count > maximum)
      throw new ApiProblem(
          429, "RATE_LIMIT", "Request rate limit exceeded. Retry in the next minute.");
  }

  private static class BodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        public int read() {
          return input.read();
        }

        public boolean isFinished() {
          return input.available() == 0;
        }

        public boolean isReady() {
          return true;
        }

        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous request bodies only.");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
