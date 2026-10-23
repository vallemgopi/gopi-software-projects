package dev.queryfence.api;

import com.fasterxml.jackson.databind.*;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class JsonConfiguration {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictJson() {
    return builder ->
        builder
            .featuresToDisable(
                DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .featuresToEnable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }
}
