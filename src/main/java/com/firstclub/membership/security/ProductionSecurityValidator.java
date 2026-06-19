package com.firstclub.membership.security;

import com.firstclub.membership.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fail-fast validation of security-critical configuration when running under the {@code prod}
 * profile. If any insecure setting is detected the application context fails to start, so a
 * production instance can never boot with a forgeable JWT key, default credentials, or a CORS
 * policy that trusts localhost/wildcard origins.
 *
 * <p>This is purely a guard: it changes no behaviour for dev/local/test profiles (the checks are
 * skipped entirely unless {@code prod} is active) and adds no new configuration — it validates the
 * existing {@link SecurityProperties} and {@code cors.allowed-origins} property.
 */
@Component
@Slf4j
public class ProductionSecurityValidator implements InitializingBean {

    static final int MIN_SECRET_BYTES = 32;
    /** Random secrets (e.g. base64 of 32 random bytes) sit well above this; trivial/repeated ones fall below. */
    static final double MIN_ENTROPY_BITS_PER_CHAR = 3.0;

    private final Environment environment;
    private final SecurityProperties properties;
    private final List<String> corsAllowedOrigins;

    public ProductionSecurityValidator(Environment environment,
                                       SecurityProperties properties,
                                       @Value("${cors.allowed-origins:}") List<String> corsAllowedOrigins) {
        this.environment = environment;
        this.properties = properties;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Override
    public void afterPropertiesSet() {
        if (!isProductionProfile(environment)) {
            return; // dev / local / test continue to work with built-in defaults
        }
        validate(properties, corsAllowedOrigins);
        log.info("Production security configuration validated — JWT secret, CORS origins and seed credentials are hardened.");
    }

    static boolean isProductionProfile(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }

    /** Runs all production security checks; throws {@link IllegalStateException} on the first violation. */
    static void validate(SecurityProperties properties, List<String> corsAllowedOrigins) {
        validateJwtSecret(properties.getJwt().getSecret());
        validateCorsOrigins(corsAllowedOrigins);
        validateSeedPasswords(properties.getSeed());
    }

    // ── FIX 1: JWT secret ────────────────────────────────────────────────────
    static void validateJwtSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw fatal("JWT secret is not configured. Set the JWT_SECRET environment variable "
                    + "(security.jwt.secret) to a strong random value of at least " + MIN_SECRET_BYTES + " bytes.");
        }
        if (SecurityProperties.Jwt.DEFAULT_SECRET.equals(secret)) {
            throw fatal("JWT secret is still the built-in development default — tokens would be forgeable. "
                    + "Set JWT_SECRET to a unique strong random value before deploying to production.");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw fatal("JWT secret is too short (" + bytes + " bytes). HMAC-SHA256 requires at least "
                    + MIN_SECRET_BYTES + " bytes (256 bits).");
        }
        double entropy = shannonEntropyBitsPerChar(secret);
        if (entropy < MIN_ENTROPY_BITS_PER_CHAR) {
            throw fatal(String.format("JWT secret has insufficient entropy (%.2f bits/char, minimum %.1f). "
                    + "Use a random value such as the base64 of 32+ random bytes.",
                    entropy, MIN_ENTROPY_BITS_PER_CHAR));
        }
    }

    /** Shannon entropy of the character distribution, in bits per character. */
    static double shannonEntropyBitsPerChar(String value) {
        if (value.isEmpty()) return 0.0;
        Map<Character, Integer> counts = new HashMap<>();
        for (char c : value.toCharArray()) {
            counts.merge(c, 1, Integer::sum);
        }
        double length = value.length();
        double entropy = 0.0;
        for (int count : counts.values()) {
            double p = count / length;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    // ── FIX 3: CORS origins ──────────────────────────────────────────────────
    static void validateCorsOrigins(List<String> origins) {
        List<String> configured = origins == null ? List.of()
                : origins.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();

        if (configured.isEmpty()) {
            throw fatal("CORS allowed origins are not configured. Set CORS_ALLOWED_ORIGINS to the explicit "
                    + "production origin(s); localhost defaults must not reach production.");
        }
        for (String origin : configured) {
            if ("*".equals(origin)) {
                throw fatal("CORS wildcard origin '*' is not allowed in production (credentials are enabled). "
                        + "List explicit origins in CORS_ALLOWED_ORIGINS.");
            }
            String lower = origin.toLowerCase();
            if (lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0")) {
                throw fatal("CORS origin '" + origin + "' targets localhost, which must not be trusted in production. "
                        + "Set CORS_ALLOWED_ORIGINS to real production origins.");
            }
            if (lower.startsWith("http://")) {
                throw fatal("CORS origin '" + origin + "' is non-TLS (http). With credentials enabled, production "
                        + "origins must be https.");
            }
        }
    }

    // ── FIX 4: seed credentials ──────────────────────────────────────────────
    static void validateSeedPasswords(SecurityProperties.Seed seed) {
        if (SecurityProperties.Seed.DEFAULT_ADMIN_PASSWORD.equals(seed.getAdminPassword())) {
            throw fatal("Seed admin password is still the built-in default. Set SEED_ADMIN_PASSWORD to a strong "
                    + "value (or provision the admin out-of-band) — default credentials must not exist in production.");
        }
        if (SecurityProperties.Seed.DEFAULT_USER_PASSWORD.equals(seed.getUserPassword())) {
            throw fatal("Seed user password is still the built-in default. Set SEED_USER_PASSWORD to a strong value — "
                    + "default credentials must not exist in production.");
        }
    }

    private static IllegalStateException fatal(String message) {
        return new IllegalStateException("FATAL: insecure production security configuration — " + message);
    }
}
