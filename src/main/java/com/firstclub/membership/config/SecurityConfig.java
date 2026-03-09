package com.firstclub.membership.config;

import com.firstclub.membership.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration.
 *
 * Strategy: stateless JWT — no HTTP session, no CSRF.
 * Public routes: auth endpoints, plan/tier catalogue, Swagger, H2 console.
 * All other routes require a valid Bearer token.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    /** True only when spring.h2.console.enabled=true (dev profile). */
    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Work factor 12 per OWASP 2025 recommendation (default 10 is underpowered)
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless API — CSRF not needed
            .csrf(AbstractHttpConfigurer::disable)
            // CORS handled by CorsConfig
            .cors(cors -> {})
            // No HTTP session
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Auth endpoints always public
                .requestMatchers("/api/v1/auth/**").permitAll()
                // Plan and tier catalogue is read-only public information
                .requestMatchers(HttpMethod.GET, "/api/v1/membership/plans/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/membership/tiers/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/plans/**").permitAll()
                // Fake gateway emulator (dev/test only — remove in production)
                .requestMatchers("/gateway/**").permitAll()
                // Inbound webhook callbacks from payment gateway (signed with HMAC)
                .requestMatchers("/api/v1/webhooks/**").permitAll()
                // Swagger UI and OpenAPI docs
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/v3/api-docs"
                ).permitAll()
                // H2 console — only when explicitly enabled (dev profile)
                .requestMatchers("/h2-console/**").access(
                    (authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(h2ConsoleEnabled)
                )
                // Actuator health endpoint is public; all other actuator endpoints require admin
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            // Allow H2 console frames (same origin)
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
            // Return 401 for unauthenticated requests (no token) — not 403
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Authentication required\",\"httpStatus\":401}"
                    );
                })
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
