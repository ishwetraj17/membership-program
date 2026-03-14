package com.firstclub.platform.redis;

import com.firstclub.membership.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RedisOpsFacade} against a real Redis container.
 *
 * <p>Validates that all facade operations ({@code setIfAbsent}, {@code get},
 * {@code set}, {@code delete}, {@code increment}, {@code expire}) work correctly
 * end-to-end with Lettuce and a live Redis 7 instance.
 *
 * <p>The Redis container is started eagerly via a static initialiser so that it
 * is guaranteed to be running before Spring creates the application context.
 */
@DisplayName("RedisOpsFacade — Integration Tests")
class RedisOpsFacadeIntegrationTest extends PostgresIntegrationTestBase {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withStartupTimeout(Duration.ofSeconds(60));

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("app.redis.enabled", () -> "true");
        registry.add("app.redis.host", REDIS::getHost);
        registry.add("app.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private RedisOpsFacade redisOpsFacade;

    @Autowired
    private RedisKeyFactory keyFactory;

    @AfterEach
    void cleanUp() {
        // Test keys use a shared prefix; delete individually for isolation
        redisOpsFacade.delete(keyFactory.featureFlagKey("ops-facade-test-flag"));
        redisOpsFacade.delete(keyFactory.featureFlagKey("ops-facade-del-test"));
        redisOpsFacade.delete(keyFactory.featureFlagKey("ops-facade-incr-test"));
        redisOpsFacade.delete(keyFactory.featureFlagKey("ops-facade-expire-test"));
    }

    // ── setIfAbsent / get ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("setIfAbsent + get round-trip")
    class SetIfAbsentTests {

        @Test
        @DisplayName("setIfAbsent returns true for new key and stores the value")
        void setIfAbsent_newKey_returnsTrueAndStoresValue() {
            String key = keyFactory.featureFlagKey("ops-facade-test-flag");

            boolean set = redisOpsFacade.setIfAbsent(key, "flag-value", Duration.ofMinutes(1));
            Optional<String> retrieved = redisOpsFacade.get(key);

            assertThat(set).isTrue();
            assertThat(retrieved).contains("flag-value");
        }

        @Test
        @DisplayName("setIfAbsent returns false when key already exists")
        void setIfAbsent_existingKey_returnsFalse() {
            String key = keyFactory.featureFlagKey("ops-facade-test-flag");
            redisOpsFacade.setIfAbsent(key, "original", Duration.ofMinutes(1));

            boolean secondSet = redisOpsFacade.setIfAbsent(key, "override", Duration.ofMinutes(1));
            Optional<String> value = redisOpsFacade.get(key);

            assertThat(secondSet).isFalse();
            assertThat(value).contains("original"); // original value preserved
        }
    }

    // ── set ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("set (unconditional)")
    class SetTests {

        @Test
        @DisplayName("set overwrites an existing value")
        void set_overwritesExistingValue() {
            String key = keyFactory.featureFlagKey("ops-facade-test-flag");
            redisOpsFacade.set(key, "first", Duration.ofMinutes(1));
            redisOpsFacade.set(key, "second", Duration.ofMinutes(1));

            assertThat(redisOpsFacade.get(key)).contains("second");
        }
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("delete removes the key and get returns empty")
        void delete_removesKey() {
            String key = keyFactory.featureFlagKey("ops-facade-del-test");
            redisOpsFacade.set(key, "to-be-deleted", Duration.ofMinutes(1));

            boolean deleted = redisOpsFacade.delete(key);
            Optional<String> afterDelete = redisOpsFacade.get(key);

            assertThat(deleted).isTrue();
            assertThat(afterDelete).isEmpty();
        }

        @Test
        @DisplayName("delete on non-existent key returns false")
        void delete_nonExistentKey_returnsFalse() {
            String key = keyFactory.featureFlagKey("ops-facade-nonexistent-xyz");
            redisOpsFacade.delete(key); // ensure it's absent
            assertThat(redisOpsFacade.delete(key)).isFalse();
        }
    }

    // ── increment ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("increment")
    class IncrementTests {

        @Test
        @DisplayName("increment starts at 1 for a new key")
        void increment_newKey_startsAtOne() {
            String key = keyFactory.featureFlagKey("ops-facade-incr-test");
            long result = redisOpsFacade.increment(key);
            assertThat(result).isEqualTo(1L);
        }

        @Test
        @DisplayName("increment returns monotonically increasing values")
        void increment_monotonicallyIncreasing() {
            String key = keyFactory.featureFlagKey("ops-facade-incr-test");
            long first = redisOpsFacade.increment(key);
            long second = redisOpsFacade.increment(key);
            long third = redisOpsFacade.increment(key);
            assertThat(second).isEqualTo(first + 1);
            assertThat(third).isEqualTo(second + 1);
        }
    }

    // ── expire ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("expire")
    class ExpireTests {

        @Test
        @DisplayName("expire returns true for an existing key")
        void expire_existingKey_returnsTrue() {
            String key = keyFactory.featureFlagKey("ops-facade-expire-test");
            redisOpsFacade.set(key, "value", Duration.ofMinutes(5));

            boolean result = redisOpsFacade.expire(key, Duration.ofMinutes(10));
            assertThat(result).isTrue();
        }
    }
}
