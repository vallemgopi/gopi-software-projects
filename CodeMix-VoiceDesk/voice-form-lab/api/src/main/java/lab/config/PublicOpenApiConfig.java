package lab.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class PublicOpenApiConfig {
  @Bean
  OpenApiCustomizer publicOperations() {
    return api ->
        api.getPaths()
            .forEach(
                (path, item) -> {
                  if (path.startsWith("/api/public/"))
                    item.readOperations().forEach(op -> op.setSecurity(java.util.List.of()));
                  if (path.startsWith("/api/phone/twilio/"))
                    item.readOperations()
                        .forEach(
                            op -> {
                              op.setSecurity(java.util.List.of());
                              op.setDescription(
                                  "Provider webhook only. Requires a valid X-Twilio-Signature,"
                                      + " configured account and canonical HTTPS origin; disabled"
                                      + " by default.");
                            });
                });
  }
}
