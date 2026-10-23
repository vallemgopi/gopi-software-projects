package dev.queryfence.api;

import dev.queryfence.planner.OllamaPlanner.ProviderException;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(dev.queryfence.security.ApiProblem.class)
  ResponseEntity<ErrorBody> policy(dev.queryfence.security.ApiProblem e) {
    return error(e.status, e.code, e.getMessage());
  }

  public record ErrorBody(Instant timestamp, String code, String message, String requestId) {}

  private ResponseEntity<ErrorBody> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorBody(Instant.now(), code, message, MDC.get("requestId")));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ErrorBody> invalid(IllegalArgumentException e) {
    return error(400, "INVALID_REQUEST", e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ErrorBody> invalidBody(Exception e) {
    return error(
        400,
        "INVALID_REQUEST",
        "Invalid request fields, values, or confirmation. Question must contain 1–500 characters.");
  }

  @ExceptionHandler(ProviderException.class)
  ResponseEntity<ErrorBody> provider(ProviderException e) {
    return error(502, "PLANNER_ERROR", e.getMessage());
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ErrorBody> database(DataAccessException e) {
    return error(
        503,
        "DATABASE_ERROR",
        "Analytics database unavailable or query timed out. Request a new plan before retrying.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> unexpected(Exception e) {
    return error(500, "INTERNAL_ERROR", "Request could not be completed.");
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<ErrorBody> notFound(Exception e) {
    return error(404, "NOT_FOUND", "Endpoint not found.");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorBody> wrongMethod(Exception e) {
    return error(405, "METHOD_NOT_ALLOWED", "HTTP method not supported for this endpoint.");
  }
}
