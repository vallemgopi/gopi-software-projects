package lab.phone;

import com.twilio.security.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PhoneWebhookVerifier {
  private final PhoneSettings settings;

  public PhoneWebhookVerifier(PhoneSettings settings) {
    this.settings = settings;
  }

  public Map<String, String> verify(HttpServletRequest request) {
    if (!settings.isEnabled())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Telephone intake is not activated.");
    if (request.getQueryString() != null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Webhook query parameters are not supported.");
    Map<String, String> fields = new TreeMap<>();
    request
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (values.length != 1)
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Duplicate webhook parameter.");
              fields.put(key, values[0]);
            });
    String signature = request.getHeader("X-Twilio-Signature");
    String url = settings.getPublicBaseUrl() + request.getRequestURI();
    if (signature == null
        || !new RequestValidator(settings.getAuthToken()).validate(url, fields, signature)
        || !settings.getAccountSid().equals(fields.get("AccountSid")))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Invalid telephone webhook signature.");
    if (!fields.getOrDefault("CallSid", "").matches("CA[0-9a-fA-F]{32}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid call identifier.");
    return fields;
  }
}
