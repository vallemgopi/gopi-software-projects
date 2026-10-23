import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.regex.*;

/** Java 21 source-file example: java integrations/examples/QueryFenceClient.java */
public class QueryFenceClient {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String base, token;

  QueryFenceClient(String base, String token) {
    this.base = base;
    this.token = token;
  }

  String call(String path, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(40))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
    if (body == null) builder.GET();
    else builder.POST(HttpRequest.BodyPublishers.ofString(body));
    var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400)
      throw new IllegalStateException(
          "API rejected request (HTTP "
              + response.statusCode()
              + "). Check scopes, role and approval state.");
    return response.body();
  }

  // Extract only server-generated identifier/token fields, never arbitrary JSON or SQL.
  static String field(String json, String key) {
    var matcher =
        Pattern.compile("\"" + key + "\"\\s*:\\s*\"([A-Za-z0-9_-]{1,100})\"").matcher(json);
    if (!matcher.find())
      throw new IllegalArgumentException(
          "Response requires clarification or is not a reviewable plan.");
    return matcher.group(1);
  }

  public static void main(String[] args) throws Exception {
    String token = System.getenv("QUERYFENCE_TOKEN");
    String file = System.getenv("QUERYFENCE_TOKEN_FILE");
    if (file != null) token = field(Files.readString(Path.of(file)), "token");
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("Configure QUERYFENCE_TOKEN_FILE or QUERYFENCE_TOKEN.");
    var api =
        new QueryFenceClient(
            System.getenv().getOrDefault("QUERYFENCE_API_URL", "http://localhost:18080/api/v2"),
            token);
    System.out.println(api.call("/schema", null));
    String review =
        api.call("/queries/plan", "{\"question\":\"Revenue by category last 30 days\"}");
    System.out.println(review);
    System.out.println(
        "Review the exact plan and estimate above. Type APPROVE to run this synthetic SELECT:");
    String confirmation =
        new BufferedReader(
                new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))
            .readLine();
    // Windows PowerShell may prefix redirected UTF-8 input with a BOM.
    if (confirmation == null
        || !"APPROVE".equals(confirmation.replace(String.valueOf((char) 0xfeff), "").strip()))
      return;
    String id = field(review, "planId"), digest = field(review, "reviewDigest");
    String approval =
        api.call(
            "/queries/" + id + "/approve",
            "{\"confirmed\":true,\"reviewDigest\":\"" + digest + "\"}");
    System.out.println(
        api.call(
            "/queries/" + id + "/execute",
            "{\"confirmed\":true,\"approvalToken\":\"" + field(approval, "approvalToken") + "\"}"));
    System.out.println(api.call("/queries/" + id + "/results?summary=true", null));
  }
}
