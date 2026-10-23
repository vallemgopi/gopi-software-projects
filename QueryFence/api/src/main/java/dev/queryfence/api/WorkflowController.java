package dev.queryfence.api;

import dev.queryfence.catalog.BusinessCatalog;
import dev.queryfence.persistence.ControlStore;
import dev.queryfence.plan.QueryPlan;
import dev.queryfence.plan.QueryPlan.*;
import dev.queryfence.planner.QueryPlanner;
import dev.queryfence.security.*;
import dev.queryfence.workflow.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    value = {"/api/v2", "/api/v1"},
    produces = "application/json")
@SecurityRequirement(name = "integrationBearer")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "Invalid fields or missing confirmation",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "401",
      description = "Missing, invalid, expired or revoked credential",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "403",
      description = "Scope, origin or role denied",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "404",
      description = "Plan does not belong to this identity and role",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "409",
      description = "Stale plan, approval or execution conflict",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "410",
      description = "Plan expired",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "413",
      description = "Request exceeds 16 KiB",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "422",
      description = "Unsupported question, SQL gate or cost budget rejection",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "429",
      description = "Rate or pending-plan limit reached",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "502",
      description = "Model provider failure or invalid output",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
  @ApiResponse(
      responseCode = "503",
      description = "Database or audit storage unavailable",
      content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
})
public class WorkflowController {
  private final AnalyticsWorkflow workflow;
  private final BusinessCatalog catalog;
  private final RolePolicy policy;
  private final ControlStore store;
  private final QueryPlanner planner;

  public WorkflowController(
      AnalyticsWorkflow workflow,
      BusinessCatalog catalog,
      RolePolicy policy,
      ControlStore store,
      QueryPlanner planner) {
    this.workflow = workflow;
    this.catalog = catalog;
    this.policy = policy;
    this.store = store;
    this.planner = planner;
  }

  public record Question(@NotBlank @Size(max = 500) String question) {}

  public record Selections(
      @NotNull @Size(min = 1, max = 5)
          Map<@Size(max = 30) String, @Size(max = 40) String> selections) {}

  public record ApprovalRequest(
      @AssertTrue boolean confirmed, @NotBlank @Size(max = 64) String reviewDigest) {}

  public record ExecutionRequest(
      @AssertTrue boolean confirmed, @NotBlank @Size(max = 100) String approvalToken) {}

  @GetMapping("/health")
  @io.swagger.v3.oas.annotations.security.SecurityRequirements
  @Operation(summary = "Liveness and configured provider; does not probe model availability")
  public Map<String, Object> health() {
    return Map.of(
        "status",
        "UP",
        "planner",
        planner.name(),
        "model",
        planner.model(),
        "providerStatus",
        planner.name().equals("demo") ? "ready" : "configured; checked during planning",
        "synthetic",
        true,
        "apiVersion",
        "2.0.0",
        "catalogVersion",
        BusinessCatalog.VERSION);
  }

  @GetMapping("/schema")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = ApiContracts.SchemaResponse.class)))
  @Operation(summary = "Discover approved semantics, role permissions and allowed fields")
  public Map<String, Object> schema(HttpServletRequest request) {
    var actor = IntegrationFilter.actor(request);
    actor.require("schema:read");
    return Map.of(
        "catalogVersion",
        BusinessCatalog.VERSION,
        "metricDefinitions",
        catalog.metrics(),
        "permissions",
        policy.permissions(actor.role()),
        "role",
        actor.role(),
        "roles",
        RolePolicy.Role.values(),
        "timeWindows",
        Window.values(),
        "referenceDate",
        "2026-01-31",
        "rows",
        1000,
        "apiVersion",
        "2.0.0");
  }

  @PostMapping("/queries/plan")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              schema =
                  @Schema(
                      oneOf = {
                        AnalyticsWorkflow.Review.class,
                        AnalyticsWorkflow.Clarification.class
                      })))
  @Operation(summary = "Create a reviewed plan, a blocked plan, or a clarification request")
  public Object plan(@Valid @RequestBody Question body, HttpServletRequest request) {
    return workflow.plan(IntegrationFilter.actor(request), body.question());
  }

  @PostMapping("/queries/{id}/clarify")
  @Operation(summary = "Resolve exactly the offered clarification fields")
  public AnalyticsWorkflow.Review clarify(
      @PathVariable UUID id, @Valid @RequestBody Selections body, HttpServletRequest request) {
    return workflow.clarify(IntegrationFilter.actor(request), id, body.selections());
  }

  @GetMapping("/queries/{id}")
  @Operation(summary = "Retrieve the current plan and SQL for review")
  public AnalyticsWorkflow.Review review(@PathVariable UUID id, HttpServletRequest request) {
    return workflow.review(IntegrationFilter.actor(request), id);
  }

  @PatchMapping("/queries/{id}")
  @Operation(summary = "Edit allow-listed fields; invalidate prior plan and approval")
  public AnalyticsWorkflow.Review edit(
      @PathVariable UUID id, @Valid @RequestBody QueryPlan body, HttpServletRequest request) {
    return workflow.edit(IntegrationFilter.actor(request), id, body);
  }

  @PostMapping("/queries/{id}/approve")
  @Operation(summary = "Explicitly approve the current review digest; returns a single-use token")
  public AnalyticsWorkflow.Approval approve(
      @PathVariable UUID id, @Valid @RequestBody ApprovalRequest body, HttpServletRequest request) {
    return workflow.approve(
        IntegrationFilter.actor(request), id, body.reviewDigest(), body.confirmed());
  }

  @PostMapping("/queries/{id}/execute")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = ApiContracts.Results.class)))
  @Operation(summary = "Execute an existing unexpired approved plan once; no SQL accepted")
  public Map<String, Object> execute(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionRequest body,
      HttpServletRequest request) {
    return workflow.execute(
        IntegrationFilter.actor(request), id, body.approvalToken(), body.confirmed());
  }

  @GetMapping("/queries/{id}/results")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = ApiContracts.Results.class)))
  @Operation(
      summary = "Retrieve stored results; optional deterministic summary cites returned rows only")
  public Map<String, Object> results(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "false") boolean summary,
      HttpServletRequest request) {
    return workflow.results(IntegrationFilter.actor(request), id, summary);
  }

  @GetMapping("/queries/history")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = ApiContracts.History.class))))
  @Operation(summary = "Persistent recent plans for this identity and role")
  public List<Map<String, Object>> history(HttpServletRequest request) {
    var actor = IntegrationFilter.actor(request);
    actor.require("query:results");
    return store.history(actor);
  }

  @GetMapping("/audit")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = ApiContracts.Audit.class))))
  @Operation(
      summary = "Persistent audit events for the caller; contains no credentials or raw questions")
  public List<Map<String, Object>> audit(HttpServletRequest request) {
    var actor = IntegrationFilter.actor(request);
    actor.require("audit:read");
    return store.auditHistory(actor);
  }
}
