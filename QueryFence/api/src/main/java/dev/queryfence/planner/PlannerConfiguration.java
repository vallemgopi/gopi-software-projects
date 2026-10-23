package dev.queryfence.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class PlannerConfiguration {
  @Bean
  QueryPlanner planner(
      @Value("${planner.mode}") String mode,
      @Value("${planner.url}") String url,
      @Value("${planner.model}") String model,
      @Value("${planner.model-version:unverified}") String modelVersion,
      ObjectMapper mapper) {
    return switch (mode) {
      case "demo" -> new DemoPlanner();
      case "ollama" -> new OllamaPlanner(url, model, modelVersion, mapper);
      default -> throw new IllegalArgumentException("PLANNER_MODE must be demo or ollama");
    };
  }
}
