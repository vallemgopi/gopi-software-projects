package dev.queryfence.planner;

import dev.queryfence.plan.QueryPlan;
import dev.queryfence.plan.QueryPlan.*;
import java.util.*;
import java.util.regex.*;

/** Deliberately narrow documented grammar. Unsupported or ambiguous requests are rejected. */
public class DemoPlanner implements QueryPlanner {
  public String modelVersion() {
    return "rules-2.0.0";
  }

  public String name() {
    return "demo";
  }

  public String model() {
    return "Rules-based (no model)";
  }

  public QueryPlan propose(String question) {
    String q = question.toLowerCase(Locale.ROOT).strip();
    if (q.matches(
            ".*\\b(delete|update|insert|drop|sql|join|customer|profit|salary|forecast|predict|email|password)\\b.*")
        || q.contains(";")
        || q.contains("--"))
      throw new IllegalArgumentException(
          "Unsupported question domain. Ask about retail revenue, order count, or return rate.");
    var metrics = new ArrayList<Metric>();
    if (q.matches(".*\\b(revenue|sales)\\b.*")) metrics.add(Metric.REVENUE);
    if (q.matches(".*(order count|number of orders|how many orders).*"))
      metrics.add(Metric.ORDER_COUNT);
    if (q.contains("return rate")) metrics.add(Metric.RETURN_RATE);
    var dimensions = new ArrayList<Dimension>();
    for (var d : Dimension.values())
      if (q.contains("by " + d.name().toLowerCase(Locale.ROOT))) dimensions.add(d);
    if (metrics.size() != 1 || dimensions.size() != 1)
      throw new IllegalArgumentException(
          "Specify exactly one metric and one grouping: by month, category, region, or status.");
    Window window = Window.ALL_TIME;
    if (q.contains("last 7 days")) window = Window.LAST_7_DAYS;
    else if (q.contains("last 30 days")) window = Window.LAST_30_DAYS;
    else if (q.contains("last 90 days")) window = Window.LAST_90_DAYS;
    else if (q.matches(".*\\b(today|yesterday|week|year|days|recent|monthly)\\b.*"))
      throw new IllegalArgumentException(
          "Use last 7 days, last 30 days, last 90 days, or all time.");
    Category category = null;
    Region region = null;
    for (var c : Category.values())
      if (Pattern.compile("\\b" + c.name().toLowerCase(Locale.ROOT) + "\\b").matcher(q).find()) {
        if (category != null) throw new IllegalArgumentException("Use one category.");
        category = c;
      }
    for (var r : Region.values())
      if (Pattern.compile("\\b" + r.name().toLowerCase(Locale.ROOT) + "\\b").matcher(q).find()) {
        if (region != null) throw new IllegalArgumentException("Use one region.");
        region = r;
      }
    int limit = 20;
    Matcher m = Pattern.compile("\\b(?:top|limit) (\\d+)\\b").matcher(q);
    if (m.find()) {
      try {
        limit = Integer.parseInt(m.group(1));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid limit.");
      }
    }
    // Reject unrecognized tokens instead of silently ignoring extra constraints.
    String remaining =
        q.replaceAll(
            "order count|number of orders|how many orders|return rate|last (7|30|90) days|all"
                + " time|(?:top|limit) \\d+",
            " ");
    remaining =
        remaining
            .replaceAll(
                "\\b(show|me|the|total|what|is|are|please|revenue|sales|by|month|category|region|status|for|in|and|electronics|home|clothing|grocery|sports|north|south|east|west|central)\\b",
                " ")
            .replaceAll("[?.,!]", " ")
            .strip();
    if (!remaining.isBlank())
      throw new IllegalArgumentException(
          "Unsupported wording or filter. Use a documented example.");
    return new QueryPlan(
        metrics.getFirst(),
        dimensions.getFirst(),
        window,
        category,
        region,
        limit,
        new dev.queryfence.catalog.BusinessCatalog().metric(metrics.getFirst()).definition());
  }
}
