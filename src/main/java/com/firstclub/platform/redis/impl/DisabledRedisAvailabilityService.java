package com.firstclub.platform.redis.impl;

import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * No-op implementation of {@link RedisAvailabilityService} used when Redis is
 * disabled ({@code app.redis.enabled=false} or property absent).
 *
 * <p>This bean is registered when the {@code app.redis.enabled} property is
 * {@code false} or missing — the mirror-image of
 * {@link RedisAvailabilityServiceImpl}, which is registered when the property
 * is {@code true}.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>{@link #isAvailable()} → {@code false}</li>
 *   <li>{@link #getStatus()} → {@link RedisStatus#DISABLED}</li>
 *   <li>{@link #getPingLatencyMs()} → {@code -1}</li>
 *   <li>{@link #getHost()} → {@code "disabled"}</li>
 *   <li>{@link #getPort()} → {@code 0}</li>
 * </ul>
 *
 * <p>Callers must never treat DISABLED as an error.  It simply means Redis
 * was intentionally not configured and all operations should use the database.
 */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledRedisAvailabilityService implements RedisAvailabilityService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public RedisStatus getStatus() {
        return RedisStatus.DISABLED;
    }

    @Override
    public long getPingLatencyMs() {
        return -1L;
    }

    @Override
    public String getHost() {
        return "disabled";
    }

    @Override
    public int getPort() {
        return 0;
    }
}
