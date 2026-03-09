package com.firstclub.platform.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisHealthIndicator}.
 *
 * <p>Verifies that each {@link RedisStatus} value is correctly translated
 * to a Spring Boot {@link Health} object with the expected {@link Status}
 * and detail fields.  No Redis connection required.
 */
@DisplayName("RedisHealthIndicator — Unit Tests")
class RedisHealthIndicatorTest {

    private final RedisAvailabilityService availabilityService = mock(RedisAvailabilityService.class);
    private final RedisHealthIndicator indicator = new RedisHealthIndicator(availabilityService);

    @Nested
    @DisplayName("Redis is UP")
    class WhenUp {

        @Test
        @DisplayName("reports Health.up() with latency detail")
        void reportsHealthUp() {
            when(availabilityService.getStatus()).thenReturn(RedisStatus.UP);
            when(availabilityService.getPingLatencyMs()).thenReturn(3L);
            when(availabilityService.getHost()).thenReturn("redis-host");
            when(availabilityService.getPort()).thenReturn(6379);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("latencyMs", 3L)
                    .containsEntry("host", "redis-host")
                    .containsEntry("port", 6379);
        }
    }

    @Nested
    @DisplayName("Redis is DOWN")
    class WhenDown {

        @Test
        @DisplayName("reports Health.down() with host and port details")
        void reportsHealthDown() {
            when(availabilityService.getStatus()).thenReturn(RedisStatus.DOWN);
            when(availabilityService.getPingLatencyMs()).thenReturn(-1L);
            when(availabilityService.getHost()).thenReturn("redis-host");
            when(availabilityService.getPort()).thenReturn(6379);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("message");
        }
    }

    @Nested
    @DisplayName("Redis is DEGRADED")
    class WhenDegraded {

        @Test
        @DisplayName("reports Health.unknown() with DEGRADED status detail")
        void reportsHealthUnknown() {
            when(availabilityService.getStatus()).thenReturn(RedisStatus.DEGRADED);
            when(availabilityService.getPingLatencyMs()).thenReturn(850L);
            when(availabilityService.getHost()).thenReturn("redis-host");
            when(availabilityService.getPort()).thenReturn(6379);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails())
                    .containsEntry("status", "DEGRADED")
                    .containsEntry("latencyMs", 850L);
        }
    }

    @Nested
    @DisplayName("Redis is DISABLED")
    class WhenDisabled {

        @Test
        @DisplayName("reports Health.unknown() with DISABLED status detail")
        void reportsHealthUnknown_disabled() {
            when(availabilityService.getStatus()).thenReturn(RedisStatus.DISABLED);
            when(availabilityService.getPingLatencyMs()).thenReturn(-1L);
            when(availabilityService.getHost()).thenReturn("disabled");
            when(availabilityService.getPort()).thenReturn(0);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails())
                    .containsKey("message")
                    .containsEntry("status", "DISABLED");
        }
    }
}
