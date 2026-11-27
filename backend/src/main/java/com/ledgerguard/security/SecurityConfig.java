package com.ledgerguard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.common.CorrelationIdFilter;
import com.ledgerguard.common.LedgerGuardProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless HTTP Basic authentication with role-based authorization enforced server-side.
 *
 * <p>CSRF protection is disabled because the API is stateless: it issues no session cookie, and
 * every request must carry credentials in the Authorization header, which a cross-site page
 * cannot attach automatically. If cookie-based sessions are ever added, CSRF must be re-enabled.
 *
 * <p>Users are five simulated demo identities sharing a password supplied by environment
 * variable (never committed). A production system would use an identity provider (OIDC).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final List<String> DEMO_USERS = List.of("viewer", "operator", "approver", "auditor", "admin");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, ex) ->
                        writeProblem(json, response, 401, "UNAUTHENTICATED", "Valid credentials are required")))
                .exceptionHandling(e -> e.accessDeniedHandler((request, response, ex) ->
                        writeProblem(json, response, 403, "FORBIDDEN", "Your role does not permit this operation")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error", "/actuator/health/**", "/actuator/info",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions/*/reversals").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/accounts/*/status").hasRole("ADMIN")
                        .requestMatchers("/api/v1/audit-events/**").hasAnyRole("AUDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/ledger/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/simulations/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasRole("VIEWER")
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(LedgerGuardProperties properties, PasswordEncoder encoder) {
        String password = properties.security() == null ? null : properties.security().demoPassword();
        if (password == null || password.length() < 12) {
            throw new IllegalStateException(
                    "LEDGERGUARD_DEMO_PASSWORD must be set to at least 12 characters (see .env.example)");
        }
        String hash = encoder.encode(password);
        return new InMemoryUserDetailsManager(
                User.withUsername("viewer").password(hash).roles("VIEWER").build(),
                User.withUsername("operator").password(hash).roles("VIEWER", "OPERATOR").build(),
                User.withUsername("approver").password(hash).roles("VIEWER", "APPROVER").build(),
                User.withUsername("auditor").password(hash).roles("VIEWER", "AUDITOR").build(),
                User.withUsername("admin").password(hash).roles("VIEWER", "OPERATOR", "APPROVER", "AUDITOR", "ADMIN").build());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(LedgerGuardProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = properties.security() == null ? List.of() : properties.security().allowedOrigins();
        if (!origins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PATCH"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", CorrelationIdFilter.HEADER));
            config.setExposedHeaders(List.of(CorrelationIdFilter.HEADER, "Idempotent-Replayed"));
            source.registerCorsConfiguration("/api/**", config);
        }
        return source;
    }

    private static void writeProblem(ObjectMapper json, HttpServletResponse response, int status, String code,
                                     String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:ledgerguard:problem:" + code.toLowerCase());
        body.put("title", status == 401 ? "Unauthorized" : "Forbidden");
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        body.put("correlationId", CorrelationIdFilter.current());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
