package com.firstclub.platform.redis;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.platform.ops.dto.RedisHealthStatusDTO;
import com.firstclub.platform.redis.impl.RedisAvailabilityServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Redis infrastructure layer.
 *
 * <p>Uses a real Redis container (via Testcontainers) and a real PostgreSQL
 * container (via {@link PostgresIntegrationTestBase}) to validate end-to-end
 * Redis wiring when {@code app.redis.enabled=true}.
 *
 * <h3>Test scope</h3>
 * <ul>
 *   <li>Redis connection factory is created and connected.</li>
 *   <li>{@link StringRedisTemplate} can set and retrieve key/value pairs.</li>
 *   <li>{@link RedisAvailabilityService#isAvailable()} returns {@code true}.</li>
 *   <li>{@link RedisAvailabilityService#getStatus()} returns {@link RedisStatus#UP}.</li>
 *   <li>{@code GET /api/v2/admin/system/redis/health} returns 200 with status "UP".</li>
 * </ul>
 *
 * <p>The Redis container is started eagerly via a static initialiser so that it
 * is guaranteed to be running before Spring creates the application context.
 */
@DisplayName("Redis Infrastructure — Integration Tests")
class RedisIntegrationTest extends PostgresIntegrationTestBase {

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
    private RedisAvailabilityService redisAvailabilityService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisKeyFactory redisKeyFactory;

    @Autowired
    private TestRestTemplate testRestTemplate;

    // ── Bean wiring ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RedisAvailabilityService bean is the live implementation")
    void redisAvailabilityService_isLiveImpl() {
        assertThat(redisAvailabilityService).isInstanceOf(RedisAvailabilityServiceImpl.class);
    }

    // ── Connection and availability ────────────────────────────────────────────

    @Test
    @DisplayName("isAvailable() returns true when Redis is running")
    void isAvailable_returnsTrueWhenRedisRunning() {
        assertThat(redisAvailabilityService.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("getStatus() returns UP when Redis is running")
    void getStatus_returnsUp() {
        assertThat(redisAvailabilityService.getStatus()).isEqualTo(RedisStatus.UP);
    }

    @Test
    @DisplayName("getPingLatencyMs() returns non-negative value when UP")
    void getPingLatencyMs_returnsNonNegative() {
        long latency = redisAvailabilityService.getPingLatencyMs();
        assertThat(latency).isGreaterThanOrEqualTo(0L);
    }

    // ── Key/value operations ───────────────────────────────────────────────────

    @Test
    @DisplayName("StringRedisTemplate can set and retrieve a key")
    void stringRedisTemplate_setAndGet() {
        String key = redisKeyFactory.featureFlagKey("integration-test-flag");
        stringRedisTemplate.opsForValue().set(key, "true");

        String value = stringRedisTemplate.opsForValue().get(key);
        assertThat(value).isEqualTo("true");

        stringRedisTemplate.delete(key);
    }

    @Test
    @DisplayName("RedisKeyFactory produces keys that can be stored and retrieved")
    void redisKeyFactory_keysRoundTrip() {
        String key = redisKeyFactory.idempotencyResponseKey("merchant-test", "idem-key-001");
        String expectedValue = "{\"status\":200,\"body\":\"ok\"}";

        stringRedisTemplate.opsForValue().set(key, expectedValue);
        String retrieved = stringRedisTemplate.opsForValue().get(key);

        assertThat(retrieved).isEqualTo(expectedValue);
        stringRedisTemplate.delete(key);
    }

    // ── Admin endpoint ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v2/admin/system/redis/health returns 200 with status UP")
    void redisHealthEndpoint_returns200WithStatusUp() {
        ResponseEntity<RedisHealthStatusDTO> response = testRestTemplate
                .withBasicAuth("admin", "admin123")
                .getForEntity("/api/v2/admin/system/redis/health", RedisHealthStatusDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().latencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(response.getBody().checkedAt()).isNotNull();
    }
}
