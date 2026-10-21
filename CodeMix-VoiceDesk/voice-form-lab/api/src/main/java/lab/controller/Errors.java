package lab.controller;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {
  private ResponseEntity<?> error(int status, String message) {
    return ResponseEntity.status(status).body(Map.of("status", status, "message", message));
  }

  @ExceptionHandler(ResponseStatusException.class)
  Object status(ResponseStatusException e) {
    return error(
        e.getStatusCode().value(), e.getReason() == null ? "Request failed" : e.getReason());
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  Object invalid(Exception e) {
    return error(
        400, e instanceof IllegalArgumentException ? e.getMessage() : "Invalid request fields");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  Object tooLarge() {
    return error(413, "Maximum audio size is 5 MiB");
  }

  @ExceptionHandler(HttpClientErrorException.class)
  Object mlInvalid(HttpClientErrorException e) {
    if (e.getStatusCode().value() == 422
        && e.getResponseBodyAsString().contains("No speech detected"))
      return error(422, "No speech detected. Record a spoken clip and try again.");
    return error(
        e.getStatusCode().value(),
        "ML rejected input. Check WAV format, provider setup, and supplied sample transcript.");
  }

  @ExceptionHandler(RestClientException.class)
  Object mlDown() {
    return error(503, "ML service unavailable; try again later");
  }

  @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
  Object conflict() {
    return error(409, "Case changed; reload before saving");
  }

  @ExceptionHandler(Exception.class)
  Object other(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("event=request_failed type={}", e.getClass().getSimpleName());
    return error(500, "Request failed; see service logs");
  }
}
