package lab.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class MlClient {
  private final RestClient client;

  public MlClient(@Value("${lab.ml-url}") String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(300000);
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public JsonNode extract(String text) {
    return client
        .post()
        .uri("/extract")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(java.util.Map.of("text", text))
        .retrieve()
        .body(JsonNode.class);
  }

  public JsonNode evaluate() {
    return client.post().uri("/evaluate").retrieve().body(JsonNode.class);
  }

  public JsonNode health() {
    return client.get().uri("/health").retrieve().body(JsonNode.class);
  }

  public JsonNode transcribe(byte[] bytes, String provider, String sample, String language) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "clip.wav";
          }
        });
    form.add("provider", provider);
    form.add("language", language);
    if (sample != null) form.add("sampleText", sample);
    return client
        .post()
        .uri("/transcribe")
        .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .retrieve()
        .body(JsonNode.class);
  }
}
