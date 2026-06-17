package com.firstclub.membership.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security configuration — JWT signing/expiry and the seed accounts.
 *
 * Every value is overridable via {@code security.*} properties / environment variables.
 * The defaults exist only so the demo runs out of the box; production MUST override the
 * JWT secret and seed passwords (see application-prod.yml notes).
 */
@ConfigurationProperties(prefix = "security")
@Data
public class SecurityProperties {

    private Jwt jwt = new Jwt();
    private Seed seed = new Seed();
    private Refresh refresh = new Refresh();
    private Lockout lockout = new Lockout();

    @Data
    public static class Jwt {
        /** HMAC-SHA256 signing secret — must be at least 32 bytes. */
        private String secret = "dev-only-change-me-this-secret-must-be-at-least-32-bytes-long-0123456789";
        private long expirationMinutes = 120;
    }

    @Data
    public static class Refresh {
        private long expirationDays = 30;
    }

    @Data
    public static class Lockout {
        private int maxAttempts = 5;
        private int windowMinutes = 15;
    }

    @Data
    public static class Seed {
        private String adminUsername = "admin";
        private String adminPassword = "admin123";
        private String userUsername = "demo";
        private String userPassword = "demo123";
    }
}
