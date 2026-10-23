package dev.queryfence.persistence;

import com.zaxxer.hikari.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseConfiguration {
  private HikariDataSource pool(String url, String user, String password, boolean readOnly) {
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(5000);
    config.setReadOnly(readOnly);
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }

  @Bean
  @Primary
  DataSource dataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${ANALYTICS_PASSWORD}") String password) {
    return pool(url, "analytics", password, true);
  }

  @Bean
  DataSource controlDataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${CONTROL_PASSWORD}") String password) {
    return pool(url, "queryfence_control", password, false);
  }

  @Bean
  @Primary
  JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource source) {
    var jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  @Bean
  JdbcTemplate controlJdbc(@Qualifier("controlDataSource") DataSource source) {
    var jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }
}
