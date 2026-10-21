package lab.dto;

import jakarta.validation.constraints.*;
import java.util.Map;

public class Requests {
  public record Create(@NotBlank @Size(max = 10000) String transcript) {}

  public record PublicText(
      @NotBlank @Size(max = 5000) String transcript,
      @NotBlank @Pattern(regexp = "auto|ta|te|kn|ml|en|hi") String language,
      @NotNull @AssertTrue Boolean consent,
      @Size(max = 0) String website) {}

  public record Review(
      @NotBlank @Size(max = 10000) String transcript,
      @NotNull Map<String, @Size(max = 2000) String> fields,
      @PositiveOrZero long version) {}
}
