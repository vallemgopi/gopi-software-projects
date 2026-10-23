package dev.queryfence.persistence;

import com.fasterxml.jackson.databind.*;
import dev.queryfence.security.*;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ControlStore {
  public final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public ControlStore(@Qualifier("controlJdbc") JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize workflow metadata.");
    }
  }

  public <T> T decode(Object value, Class<T> type) {
    try {
      return mapper.readValue(value.toString(), type);
    } catch (Exception e) {
      throw new IllegalStateException("Stored workflow metadata is invalid.");
    }
  }

  public JsonNode tree(Object value) {
    try {
      return mapper.readTree(value.toString());
    } catch (Exception e) {
      throw new IllegalStateException("Stored workflow metadata is invalid.");
    }
  }

  public Map<String, Object> plan(UUID id, Actor actor) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM queryfence.plans WHERE id=? AND actor=? AND role=?",
            id,
            actor.id(),
            actor.role().name());
    if (rows.isEmpty())
      throw new ApiProblem(404, "PLAN_NOT_FOUND", "Plan not found for this identity and role.");
    return rows.getFirst();
  }

  public Instant instant(Object value) {
    return ((java.sql.Timestamp) value).toInstant();
  }

  public void audit(
      Actor actor,
      String action,
      UUID planId,
      String outcome,
      boolean approved,
      Map<String, ?> details) {
    var metadata = new LinkedHashMap<String, Object>(details);
    metadata.put("applicationVersion", "2.0.0");
    jdbc.update(
        "INSERT INTO"
            + " queryfence.audit_events(request_id,actor,role,action,plan_id,outcome,approved,details)"
            + " VALUES (?,?,?,?,?,?,?,?::jsonb)",
        MDC.get("requestId"),
        actor.id(),
        actor.role().name(),
        action,
        planId,
        outcome,
        approved,
        json(metadata));
  }

  public List<Map<String, Object>> history(Actor actor) {
    return jdbc
        .queryForList(
            "SELECT id,plan,role,status,version,planner,model,confirmation,elapsed_ms,created_at"
                + " FROM queryfence.plans WHERE actor=? AND role=? ORDER BY created_at DESC LIMIT"
                + " 100",
            actor.id(),
            actor.role().name())
        .stream()
        .map(
            row -> {
              var r = new LinkedHashMap<>(row);
              if (r.get("plan") != null) r.put("plan", tree(r.get("plan")));
              return (Map<String, Object>) r;
            })
        .toList();
  }

  public List<Map<String, Object>> auditHistory(Actor actor) {
    return jdbc
        .queryForList(
            "SELECT at,request_id,actor,role,action,plan_id,outcome,approved,details FROM"
                + " queryfence.audit_events WHERE actor=? AND role=? ORDER BY at DESC,id DESC LIMIT"
                + " 100",
            actor.id(),
            actor.role().name())
        .stream()
        .map(
            row -> {
              var r = new LinkedHashMap<>(row);
              r.put("details", tree(r.get("details")));
              return (Map<String, Object>) r;
            })
        .toList();
  }
}
