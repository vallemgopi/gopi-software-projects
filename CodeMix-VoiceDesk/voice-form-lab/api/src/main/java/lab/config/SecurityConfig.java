package lab.config;

import java.util.List;
import lab.domain.LabUser;
import lab.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean
  BCryptPasswordEncoder encoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(UserRepository repo) {
    return name ->
        repo.findByUsername(name)
            .map(
                u ->
                    User.withUsername(u.username)
                        .password(u.passwordHash)
                        .roles("REVIEWER")
                        .build())
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
  }

  @Bean
  CommandLineRunner seed(
      UserRepository repo,
      BCryptPasswordEncoder encoder,
      @Value("${lab.demo-user}") String name,
      @Value("${lab.demo-password}") String password) {
    return args -> {
      if (password.length() < 12)
        throw new IllegalStateException("DEMO_PASSWORD must be at least 12 characters");
      LabUser u = repo.findByUsername(name).orElseGet(LabUser::new);
      u.username = name;
      u.passwordHash = encoder.encode(password);
      repo.save(u);
    };
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http, @Value("${lab.cors-origin}") String origin)
      throws Exception {
    return http.cors(c -> {})
        .addFilterBefore(
            new BrowserOriginFilter(origin),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .csrf(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/public/config")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/phone/twilio/voice",
                        "/api/phone/twilio/language",
                        "/api/phone/twilio/consent",
                        "/api/phone/twilio/recorded",
                        "/api/phone/twilio/recording-status")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/public/submissions",
                        "/api/public/submissions/audio")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(
            c ->
                c.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/json");
                      res.getWriter().write("{\"message\":\"Authentication required\"}");
                    }))
        .build();
  }

  @Bean
  CorsConfigurationSource cors(@Value("${lab.cors-origin}") String origin) {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of(origin));
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
    s.registerCorsConfiguration("/**", c);
    return s;
  }
}
