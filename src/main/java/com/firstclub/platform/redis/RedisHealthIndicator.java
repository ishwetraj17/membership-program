package com.firstclub.platform.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator {@link HealthIndicator} for Redis.
 *
 * <p>Contributes a {@code "redis"} entry to the
 * {@code GET /actuator/health} endpoint when Redis is enabled.
 *
 * <p>This indicator is only registered when {@code app.redis.enabled=true}.
 * When Redis is disabled, the key is simply absent from the health response
 * (the platform operates healthily without Redis).
 *
 * <h3>Status mapping</h3>
 * <pre>
 * RedisStatus.UP       → Health.up()       (latencyMs detail included)
 * RedisStatus.DEGRADED → Health.unknown()  (latencyMs detail included)
 * RedisStatus.DOWN     → Health.down()     (details included, no exception exposed)
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisAvailabilityService redisAvailabilityService;

    @Override
    public Health health() {
        RedisStatus status = redisAvailabilityService.getStatus();
        long latencyMs = redisAvailabilityService.getPingLatencyMs();
        String host = redisAvailabilityService.getHost();
        int port = redisAvailabilityService.getPort();

        return switch (status) {
            case UP -> Health.up()
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .withDetail("latencyMs", latencyMs)
                    .build();
            case DEGRADED -> Health.unknown()
                    .withDetail("status", "DEGRADED")
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .withDetail("latencyMs", latencyMs)
                    .build();
            case DOWN -> Health.down()
                    .withDetail("status", "DOWN")
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .withDetail("message", "Redis is unreachable or not responding to PING")
                    .build();
            case DISABLED -> Health.unknown()
                    .withDetail("status", "DISABLED")
                    .withDetail("message", "Redis is disabled via app.redis.enabled=false")
                    .build();
        };
    }
}
