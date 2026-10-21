package lab.validation;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ReviewValidator {
  public void validate(Map<String, String> fields) {
    if (!fields
        .keySet()
        .equals(Set.of("category", "summary", "location", "datetime", "identifier")))
      throw new IllegalArgumentException(
          "Provide exactly the five review fields; blank values confirm missing information");
    String c = fields.get("category");
    if (c != null
        && !c.isBlank()
        && !Set.of("water", "electricity", "roads", "sanitation").contains(c))
      throw new IllegalArgumentException("Invalid category");
  }
}
