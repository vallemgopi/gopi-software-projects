package dev.queryfence.api;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
  @Bean
  org.springdoc.core.customizers.OpenApiCustomizer publicHealthContract() {
    return api ->
        api.getPaths()
            .forEach(
                (path, item) -> {
                  if ((path.equals("/api/v1/health") || path.equals("/api/v2/health"))
                      && item.getGet() != null) item.getGet().setSecurity(null);
                });
  }

  @Bean
  OpenAPI openAPI() {
    return new OpenAPI()
        .servers(
            java.util.List.of(
                new io.swagger.v3.oas.models.servers.Server()
                    .url("/")
                    .description("Same-origin proxy or direct QueryFence API")))
        .info(
            new Info()
                .title("QueryFence integration API")
                .version("2.0.0")
                .description(
                    "Synthetic retail only. Plans require review digest approval and a single-use"
                        + " approval token. v1 aliases share v2 policy enforcement."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "integrationBearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description(
                            "Revocable opaque token with immutable role, scopes, origins and"
                                + " expiry. Issued by the local owner CLI.")));
  }
}
