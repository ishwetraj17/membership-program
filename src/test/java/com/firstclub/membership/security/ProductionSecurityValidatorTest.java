package com.firstclub.membership.security;

import com.firstclub.membership.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Production security validator — fail-fast hardening")
class ProductionSecurityValidatorTest {

    /** A strong, high-entropy secret (44 chars / bytes, many distinct symbols). */
    private static final String STRONG_SECRET = "Yk9R2mZ7xQ4Lp8Wv1Tc6Bn3Df5Gh0JsAa2Bc4De6Fg8H";
    private static final String VALID_ORIGIN = "https://app.firstclub.example";

    private SecurityProperties hardenedProperties() {
        SecurityProperties p = new SecurityProperties();
        p.getJwt().setSecret(STRONG_SECRET);
        p.getSeed().setAdminPassword("a-strong-admin-secret");
        p.getSeed().setUserPassword("a-strong-user-secret");
        return p;
    }

    // ── FIX 1: JWT secret ────────────────────────────────────────────────────
    @Nested @DisplayName("JWT secret")
    class JwtSecret {

        @Test @DisplayName("default development secret is rejected")
        void defaultRejected() {
            assertThatThrownBy(() -> ProductionSecurityValidator.validateJwtSecret(SecurityProperties.Jwt.DEFAULT_SECRET))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("built-in development default");
        }

        @Test @DisplayName("missing/blank secret is rejected")
        void blankRejected() {
            assertThatThrownBy(() -> ProductionSecurityValidator.validateJwtSecret("  "))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
        }

        @Test @DisplayName("too-short secret is rejected")
        void shortRejected() {
            assertThatThrownBy(() -> ProductionSecurityValidator.validateJwtSecret("short-secret-key"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("too short");
        }

        @Test @DisplayName("long but low-entropy secret is rejected")
        void lowEntropyRejected() {
            String repeated = "a".repeat(48); // 48 bytes, zero entropy
            assertThatThrownBy(() -> ProductionSecurityValidator.validateJwtSecret(repeated))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("insufficient entropy");
        }

        @Test @DisplayName("strong random secret is accepted")
        void strongAccepted() {
            assertThatCode(() -> ProductionSecurityValidator.validateJwtSecret(STRONG_SECRET))
                    .doesNotThrowAnyException();
        }
    }

    // ── FIX 3: CORS origins ──────────────────────────────────────────────────
    @Nested @DisplayName("CORS origins")
    class Cors {

        @Test @DisplayName("empty configuration is rejected")
        void emptyRejected() {
            assertThatThrownBy(() -> ProductionSecurityValidator.validateCorsOrigins(List.of("", "  ")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
        }

        @Test @DisplayName("wildcard origin is rejected")
        void wildcardRejected() {
            assertThatThrownBy(() -> ProductionSecurityValidator.validateCorsOrigins(List.of("*")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wildcard");
        }

        @Test @DisplayName("localhost origin is rejected")
        void localhostRejected() {
            assertThatThrownBy(() -> ProductionSecurityValidator.validateCorsOrigins(List.of("http://localhost:3000")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("localhost");
        }

        @Test @DisplayName("non-TLS (http) origin is rejected")
        void httpRejected() {
            assertThatThrownBy(() -> ProductionSecurityValidator.validateCorsOrigins(List.of("http://app.example.com")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-TLS");
        }

        @Test @DisplayName("explicit https origin is accepted")
        void httpsAccepted() {
            assertThatCode(() -> ProductionSecurityValidator.validateCorsOrigins(List.of(VALID_ORIGIN)))
                    .doesNotThrowAnyException();
        }
    }

    // ── FIX 4: seed credentials ──────────────────────────────────────────────
    @Nested @DisplayName("Seed credentials")
    class Seed {

        @Test @DisplayName("default admin password is rejected")
        void defaultAdminRejected() {
            SecurityProperties.Seed seed = new SecurityProperties.Seed(); // defaults
            assertThatThrownBy(() -> ProductionSecurityValidator.validateSeedPasswords(seed))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("admin password");
        }

        @Test @DisplayName("default user password is rejected")
        void defaultUserRejected() {
            SecurityProperties.Seed seed = new SecurityProperties.Seed();
            seed.setAdminPassword("a-strong-admin-secret"); // only user left default
            assertThatThrownBy(() -> ProductionSecurityValidator.validateSeedPasswords(seed))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("user password");
        }

        @Test @DisplayName("non-default seed passwords are accepted")
        void strongAccepted() {
            SecurityProperties.Seed seed = new SecurityProperties.Seed();
            seed.setAdminPassword("a-strong-admin-secret");
            seed.setUserPassword("a-strong-user-secret");
            assertThatCode(() -> ProductionSecurityValidator.validateSeedPasswords(seed))
                    .doesNotThrowAnyException();
        }
    }

    // ── Startup behaviour by profile ─────────────────────────────────────────
    @Nested @DisplayName("Startup (afterPropertiesSet)")
    class Startup {

        private ProductionSecurityValidator validator(StandardEnvironment env, SecurityProperties props, List<String> origins) {
            return new ProductionSecurityValidator(env, props, origins);
        }

        private StandardEnvironment profile(String... profiles) {
            StandardEnvironment env = new StandardEnvironment();
            env.setActiveProfiles(profiles);
            return env;
        }

        @Test @DisplayName("prod with default JWT secret prevents startup")
        void prodInsecureFails() {
            // new SecurityProperties() == built-in defaults (insecure)
            ProductionSecurityValidator v = validator(profile("prod"), new SecurityProperties(), List.of(VALID_ORIGIN));
            assertThatThrownBy(v::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FATAL: insecure production security configuration");
        }

        @Test @DisplayName("prod with hardened config allows startup")
        void prodSecureStarts() {
            ProductionSecurityValidator v = validator(profile("prod"), hardenedProperties(), List.of(VALID_ORIGIN));
            assertThatCode(v::afterPropertiesSet).doesNotThrowAnyException();
        }

        @Test @DisplayName("non-prod profile skips validation (dev/local keep working)")
        void devSkipsValidation() {
            // Insecure defaults + empty CORS, but dev profile → no validation, no failure.
            ProductionSecurityValidator v = validator(profile("dev"), new SecurityProperties(), List.of());
            assertThatCode(v::afterPropertiesSet).doesNotThrowAnyException();
        }
    }
}
