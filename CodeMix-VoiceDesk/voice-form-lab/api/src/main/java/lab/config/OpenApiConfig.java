package lab.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI documentation() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Code-Mixed Voice Form Reliability Lab API")
                .version("1.0.0")
                .description(
                    "Owned cases, immutable model predictions, correction revisions, and synthetic"
                        + " evaluation. No institutional submissions."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "basicAuth",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
        .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
  }
}
