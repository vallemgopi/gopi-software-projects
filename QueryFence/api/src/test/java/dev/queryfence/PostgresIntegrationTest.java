package dev.queryfence;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @Test
  void realRoleRejectsWritesEvenWhenReadOnlyFlagDisabled() throws Exception {
    try (var owner =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var s = owner.createStatement()) {
      s.execute(
          "CREATE ROLE analytics LOGIN PASSWORD 'ephemeral-test-only' NOSUPERUSER NOCREATEDB"
              + " NOCREATEROLE NOINHERIT");
      s.execute("ALTER ROLE analytics SET default_transaction_read_only=on");
      s.execute("ALTER ROLE analytics SET statement_timeout='5s'");
      s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      s.execute("REVOKE TEMPORARY ON DATABASE test FROM PUBLIC");
    }
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    try (var c =
            DriverManager.getConnection(postgres.getJdbcUrl(), "analytics", "ephemeral-test-only");
        var s = c.createStatement()) {
      var result = s.executeQuery("SELECT count(*), sum(order_amount) FROM analytics.orders");
      assertTrue(result.next());
      assertEquals(1000, result.getInt(1));
      long cents = 0;
      for (int i = 1; i <= 1000; i++) cents += (i * 7919L) % 90000 + 100;
      assertEquals(0, java.math.BigDecimal.valueOf(cents, 2).compareTo(result.getBigDecimal(2)));
      result = s.executeQuery("SELECT count(*) FROM analytics.orders WHERE region='NORTH'");
      result.next();
      assertEquals(200, result.getInt(1));
      result = s.executeQuery("SHOW default_transaction_read_only");
      result.next();
      assertEquals("on", result.getString(1));
      s.execute("SET default_transaction_read_only=off");
      for (String sql :
          new String[] {
            "INSERT INTO analytics.orders SELECT * FROM analytics.orders LIMIT 1",
            "UPDATE analytics.orders SET returned=true",
            "DELETE FROM analytics.orders",
            "DROP TABLE analytics.orders",
            "CREATE TABLE public.evil (id int)"
          }) assertThrows(SQLException.class, () -> s.execute(sql), sql);
      var validator =
          new dev.queryfence.plan.PlanValidator(
              jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
      var compiler = new dev.queryfence.query.SqlCompiler(validator);
      for (var metric : dev.queryfence.plan.QueryPlan.Metric.values())
        for (var dimension : dev.queryfence.plan.QueryPlan.Dimension.values()) {
          var q =
              compiler.compile(
                  new dev.queryfence.plan.QueryPlan(
                      metric,
                      dimension,
                      dev.queryfence.plan.QueryPlan.Window.ALL_TIME,
                      null,
                      null,
                      20,
                      "test"));
          try (var ps = c.prepareStatement(q.sql())) {
            for (int i = 0; i < q.parameters().size(); i++)
              ps.setObject(i + 1, q.parameters().get(i));
            try (var rows = ps.executeQuery()) {
              assertTrue(rows.next());
              assertNotNull(rows.getObject("value"));
            }
          }
        }
    }
  }
}
