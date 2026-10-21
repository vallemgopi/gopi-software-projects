package lab.phone;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lab.phone")
public class PhoneSettings {
  private boolean enabled;
  private String publicBaseUrl = "",
      accountSid = "",
      authToken = "",
      apiKeySid = "",
      apiKeySecret = "",
      publicNumber = "",
      promptsBaseUrl = "";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean x) {
    enabled = x;
  }

  public String getPublicBaseUrl() {
    return publicBaseUrl.replaceAll("/+$", "");
  }

  public void setPublicBaseUrl(String x) {
    publicBaseUrl = x;
  }

  public String getAccountSid() {
    return accountSid;
  }

  public void setAccountSid(String x) {
    accountSid = x;
  }

  public String getAuthToken() {
    return authToken;
  }

  public void setAuthToken(String x) {
    authToken = x;
  }

  public String getApiKeySid() {
    return apiKeySid;
  }

  public void setApiKeySid(String x) {
    apiKeySid = x;
  }

  public String getApiKeySecret() {
    return apiKeySecret;
  }

  public void setApiKeySecret(String x) {
    apiKeySecret = x;
  }

  public String getPublicNumber() {
    return publicNumber;
  }

  public void setPublicNumber(String x) {
    publicNumber = x;
  }

  public String getPromptsBaseUrl() {
    return promptsBaseUrl.replaceAll("/+$", "");
  }

  public void setPromptsBaseUrl(String x) {
    promptsBaseUrl = x;
  }

  @PostConstruct
  public void validate() {
    if (!enabled) return;
    if (!accountSid.matches("AC[0-9a-fA-F]{32}")
        || !apiKeySid.matches("SK[0-9a-fA-F]{32}")
        || authToken.isBlank()
        || apiKeySecret.isBlank()
        || !publicNumber.matches("\\+[1-9][0-9]{7,14}"))
      throw new IllegalStateException(
          "Phone intake requires an account, signing token, media API key, and inbound E.164"
              + " number. Set them in environment variables.");
    URI uri = URI.create(getPublicBaseUrl());
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getFragment() != null
        || !uri.getRawPath().isEmpty())
      throw new IllegalStateException(
          "PHONE_PUBLIC_BASE_URL must be an HTTPS origin without path, query, or credentials.");
    if (!promptsBaseUrl.isBlank()) {
      URI prompts = URI.create(promptsBaseUrl);
      if (!"https".equals(prompts.getScheme())
          || prompts.getHost() == null
          || prompts.getUserInfo() != null
          || prompts.getQuery() != null
          || prompts.getFragment() != null)
        throw new IllegalStateException(
            "PHONE_PROMPTS_BASE_URL must be an HTTPS URL without query or credentials.");
    }
  }
}
