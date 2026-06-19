package com.firstclub.membership.security;

import com.firstclub.membership.exception.ApiErrorResponder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT security.
 *
 * Public: auth, swagger, health, self-registration, catalog browsing (GET plans/tiers).
 * ADMIN: analytics, the admin subscription/user listings, user deletion.
 * Everything else requires an authenticated principal.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiErrorResponder errorResponder;

    @Value("${cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Health is public for load-balancer / k8s probes; the Prometheus scrape
                // endpoint (and any other actuator endpoint) is operator-only.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").hasRole("ADMIN")
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/membership/health").permitAll()
                // Self-registration
                .requestMatchers(HttpMethod.POST, "/api/v1/users").permitAll()
                // Catalog browsing
                .requestMatchers(HttpMethod.GET, "/api/v1/plans/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/membership/plans/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/membership/tiers/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/membership/benefits").permitAll()
                // Admin-only
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/membership/analytics").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/membership/tiers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/membership/benefits").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/membership/tiers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/plans").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/plans/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/coupons").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/coupons").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/coupons/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/membership/subscriptions").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/users").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/users/**").hasRole("ADMIN")
                // Everything else needs a valid token
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    errorResponder.write(res, jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED,
                            "UNAUTHORIZED", "Authentication required"))
                .accessDeniedHandler((req, res, e) ->
                    errorResponder.write(res, jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN,
                            "FORBIDDEN", "Insufficient privileges")))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
