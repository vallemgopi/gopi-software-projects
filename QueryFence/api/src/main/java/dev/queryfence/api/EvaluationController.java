package dev.queryfence.api;

import dev.queryfence.evaluation.EvaluationService;
import dev.queryfence.security.IntegrationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v2/evaluations", produces = "application/json")
@SecurityRequirement(name = "integrationBearer")
public class EvaluationController {
  private final EvaluationService evaluations;

  public EvaluationController(EvaluationService evaluations) {
    this.evaluations = evaluations;
  }

  @GetMapping
  @Operation(summary = "Measured evaluation runs and saved baselines for this identity")
  public Map<String, Object> list(HttpServletRequest request) {
    return evaluations.list(IntegrationFilter.actor(request));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Start a bounded background benchmark; poll GET for completion")
  public Map<String, Object> run(HttpServletRequest request) {
    return evaluations.run(IntegrationFilter.actor(request));
  }

  @PostMapping("/{id}/baseline")
  @Operation(summary = "Save an actual completed run as a versioned baseline")
  public Map<String, Object> baseline(@PathVariable UUID id, HttpServletRequest request) {
    return evaluations.baseline(IntegrationFilter.actor(request), id);
  }
}
