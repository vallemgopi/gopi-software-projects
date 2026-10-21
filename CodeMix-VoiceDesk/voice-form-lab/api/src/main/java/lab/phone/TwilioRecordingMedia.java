package lab.phone;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class TwilioRecordingMedia implements RecordingMedia {
  private final PhoneSettings settings;
  private final HttpClient client;

  @org.springframework.beans.factory.annotation.Autowired
  public TwilioRecordingMedia(PhoneSettings settings) {
    this(
        settings,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public TwilioRecordingMedia(PhoneSettings settings, HttpClient client) {
    this.settings = settings;
    this.client = client;
  }

  public URI mediaUri(String sid, String extension) {
    if (!sid.matches("RE[0-9a-fA-F]{32}") || !settings.getAccountSid().matches("AC[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Invalid provider recording identifier");
    // Never use callback-supplied RecordingUrl: fixed provider origin prevents SSRF/credential
    // leaks.
    return URI.create(
        "https://api.twilio.com/2010-04-01/Accounts/"
            + settings.getAccountSid()
            + "/Recordings/"
            + sid
            + extension);
  }

  private HttpRequest.Builder request(String sid, String extension) {
    String auth =
        Base64.getEncoder()
            .encodeToString(
                (settings.getApiKeySid() + ":" + settings.getApiKeySecret())
                    .getBytes(StandardCharsets.UTF_8));
    return HttpRequest.newBuilder(mediaUri(sid, extension))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Basic " + auth);
  }

  @Override
  public byte[] download(String sid) throws Exception {
    var response =
        client.send(request(sid, ".wav").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var stream = response.body()) {
      if (response.statusCode() != 200) throw new java.io.IOException("Provider media unavailable");
      byte[] audio = stream.readNBytes(5 * 1024 * 1024 + 1);
      if (audio.length > 5 * 1024 * 1024
          || audio.length < 12
          || !new String(audio, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
          || !new String(audio, 8, 4, StandardCharsets.US_ASCII).equals("WAVE"))
        throw new IllegalArgumentException("Invalid or oversized recording");
      return audio;
    }
  }

  @Override
  public void delete(String sid) throws Exception {
    var response =
        client.send(request(sid, ".json").DELETE().build(), HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() != 204 && response.statusCode() != 404)
      throw new java.io.IOException("Provider cleanup unavailable");
  }
}
