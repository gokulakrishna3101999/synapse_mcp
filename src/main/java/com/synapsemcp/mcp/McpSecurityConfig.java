package com.synapsemcp.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * mcp_plan.md Stage 1, Grooming #11/#12 - the first Spring Security dependency in this project.
 * REST auth ({@code ApiKeyAuthenticationFilter}) and {@code /actuator/**} are untouched: the
 * catch-all chain below permits every non-MCP request through unconditionally, so Spring Security
 * adds no gating anywhere except the MCP transport path itself. Two ordered {@link
 * SecurityFilterChain} beans are required because a single chain can't apply HTTP Basic to one path
 * and none to everything else - Spring Security evaluates ordered chains in turn and uses the first
 * one whose {@code securityMatcher} matches the request.
 */
@Configuration
@EnableWebSecurity
public class McpSecurityConfig {

    private static final String MCP_TRANSPORT_PATH = "/mcp";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) {
        try {
            http.securityMatcher(MCP_TRANSPORT_PATH)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(
                            session ->
                                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            return http.build();
        } catch (Exception e) {
            // HttpSecurity.build() declares a broad checked Exception for API generality - nothing
            // in this fixed, lambda-only configuration (no custom configurer) genuinely throws a
            // checked exception at runtime, so this is caught and rethrown unchecked rather than
            // widening this method's own signature to the same overly-broad declared type.
            throw new IllegalStateException("failed to configure the MCP security filter chain", e);
        }
    }

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) {
        try {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable);
            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to configure the default (non-MCP) security filter chain", e);
        }
    }
}
