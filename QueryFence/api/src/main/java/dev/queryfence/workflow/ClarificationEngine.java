package dev.queryfence.workflow;

import dev.queryfence.plan.QueryPlan;
import dev.queryfence.plan.QueryPlan.*;
import dev.queryfence.security.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class ClarificationEngine {
  public record Choice(String field, String question, List<String> options) {}

  public record Draft(Map<String, String> fields, List<Choice> questions) {}

  public void guard(String question) {
    String q = question.toLowerCase(Locale.ROOT);
    if (q.length() > 500
        || q.isBlank()
        || q.matches(
            "(?s).*\\b(ignore|override|instructions|system"
                + " prompt|secrets?|passwords?|tokens?|credentials?|bypass|admin|delete|insert|update|drop|truncate|alter|grant|revoke|select|union|sql|join|customers?|emails?|salary|profit|forecast|predict|weather|president)\\b.*")
        || q.contains(";")
        || q.contains("--")
        || q.contains("/*"))
      throw new ApiProblem(
          422,
          "UNSUPPORTED_QUESTION",
          "Unsupported or adversarial request. Ask only about approved synthetic retail metrics and"
              + " groupings.");
    if (!q.matches("(?s).*\\b(revenue|sales|orders|order|returns|return|retail)\\b.*"))
      throw new ApiProblem(
          422, "UNSUPPORTED_QUESTION", "This question is outside the synthetic retail domain.");
  }

  public Draft analyze(String question, RolePolicy.Permissions permissions) {
    guard(question);
    String q = question.toLowerCase(Locale.ROOT);
    var fields = new LinkedHashMap<String, String>();
    var choices = new ArrayList<Choice>();
    var metrics = new ArrayList<String>();
    if (q.matches("(?s).*\\b(revenue|sales)\\b.*")) metrics.add("REVENUE");
    if (q.matches("(?s).*(order count|number of orders|how many orders).*"))
      metrics.add("ORDER_COUNT");
    if (q.contains("return rate")) metrics.add("RETURN_RATE");
    resolve(
        "metric",
        "Which metric do you mean?",
        metrics,
        permissions.metrics().stream().map(Enum::name).sorted().toList(),
        fields,
        choices);
    var dimensions = new ArrayList<String>();
    for (var d : Dimension.values())
      if (q.contains("by " + d.name().toLowerCase(Locale.ROOT))) dimensions.add(d.name());
    resolve(
        "dimension",
        "How should the results be grouped?",
        dimensions,
        permissions.dimensions().stream().map(Enum::name).sorted().toList(),
        fields,
        choices);
    var windows = new ArrayList<String>();
    for (int days : new int[] {7, 30, 90})
      if (q.contains("last " + days + " days")) windows.add("LAST_" + days + "_DAYS");
    if (q.contains("all time")) windows.add("ALL_TIME");
    resolve(
        "timeWindow",
        "Which date window should be used, relative to 2026-01-31?",
        windows,
        Arrays.stream(Window.values()).map(Enum::name).toList(),
        fields,
        choices);
    for (var c : Category.values())
      if (Pattern.compile("\\b" + c.name().toLowerCase(Locale.ROOT) + "\\b").matcher(q).find()) {
        if (fields.put("category", c.name()) != null)
          throw new ApiProblem(422, "UNSUPPORTED_FILTER", "Select one category.");
      }
    for (var r : Region.values())
      if (Pattern.compile("\\b" + r.name().toLowerCase(Locale.ROOT) + "\\b").matcher(q).find()) {
        if (fields.put("region", r.name()) != null)
          throw new ApiProblem(422, "UNSUPPORTED_FILTER", "Select one region.");
      }
    if (permissions.categoryRequired() && !fields.containsKey("category"))
      choices.add(
          new Choice(
              "category",
              "Select a category allowed by this role.",
              permissions.categories().stream().map(Enum::name).sorted().toList()));
    if (permissions.regionRequired() && !fields.containsKey("region"))
      choices.add(
          new Choice(
              "region",
              "Select a region allowed by this role.",
              permissions.regions().stream().map(Enum::name).sorted().toList()));
    var limit = Pattern.compile("\\b(?:top|limit) (\\d+)\\b").matcher(q);
    fields.put("limit", limit.find() ? limit.group(1) : "20");
    // An unrecognized constraint must not disappear when the user supplies missing fields.
    String remaining =
        q.replaceAll(
                "order count|number of orders|how many orders|return rate|last (7|30|90) days|all"
                    + " time|last month|recently|recent|(?:top|limit) \\d+",
                " ")
            .replaceAll(
                "\\b(show|me|the|total|what|is|are|please|revenue|sales|by|month|category|region|status|for|in|and|or|electronics|home|clothing|grocery|sports|north|south|east|west|central|retail)\\b",
                " ")
            .replaceAll("[?.,!]", " ")
            .strip();
    if (!remaining.isBlank())
      throw new ApiProblem(
          422,
          "UNSUPPORTED_QUESTION",
          "Unsupported wording or filter. Use the catalog and supported controls.");
    return new Draft(Map.copyOf(fields), List.copyOf(choices));
  }

  private void resolve(
      String field,
      String question,
      List<String> found,
      List<String> allowed,
      Map<String, String> fields,
      List<Choice> choices) {
    if (found.size() == 1) {
      if (!allowed.contains(found.getFirst()))
        throw new ApiProblem(
            403, "ROLE_POLICY", "Role does not allow the requested " + field + ".");
      fields.put(field, found.getFirst());
    } else {
      var options = found.isEmpty() ? allowed : found.stream().filter(allowed::contains).toList();
      if (options.isEmpty())
        throw new ApiProblem(403, "ROLE_POLICY", "No requested option is allowed for this role.");
      choices.add(new Choice(field, question, options));
    }
  }

  public QueryPlan complete(Draft draft, Map<String, String> selections) {
    var f = new HashMap<>(draft.fields());
    var required =
        draft.questions().stream().map(Choice::field).collect(java.util.stream.Collectors.toSet());
    if (!selections.keySet().equals(required))
      throw new ApiProblem(
          400, "INVALID_CLARIFICATION", "Supply exactly the requested clarification fields.");
    for (var c : draft.questions()) {
      var selected = selections.get(c.field());
      if (!c.options().contains(selected))
        throw new ApiProblem(
            400, "INVALID_CLARIFICATION", "Selection is outside the offered options.");
      f.put(c.field(), selected);
    }
    try {
      return new QueryPlan(
          Metric.valueOf(f.get("metric")),
          Dimension.valueOf(f.get("dimension")),
          Window.valueOf(f.get("timeWindow")),
          f.containsKey("category") ? Category.valueOf(f.get("category")) : null,
          f.containsKey("region") ? Region.valueOf(f.get("region")) : null,
          Integer.parseInt(f.get("limit")),
          "User-selected clarification.");
    } catch (Exception e) {
      throw new ApiProblem(400, "INVALID_CLARIFICATION", "Invalid clarification values.");
    }
  }
}
