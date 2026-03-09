package com.firstclub.platform.redis.impl;

import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisProperties;
import com.firstclub.platform.redis.RedisStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Live implementation of {@link RedisAvailabilityService}.
 *
 * <p>Registered only when {@code app.redis.enabled=true}; otherwise
 * {@link DisabledRedisAvailabilityService} provides the no-op fallback.
 *
 * <h3>Status determination</h3>
 * <ul>
 *   <li>PING returns "PONG" within command timeout → {@link RedisStatus#UP}</li>
 *   <li>PING returns unexpected value → {@link RedisStatus#DEGRADED}</li>
 *   <li>Any exception (connection refused, timeout, auth failure) → {@link RedisStatus#DOWN}</li>
 * </ul>
 *
 * <p>Each call to {@link #getStatus()} issues a live PING. The overhead is a
 * single lightweight command; callers that are latency-sensitive should cache
 * the result themselves as appropriate.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisAvailabilityServiceImpl implements RedisAvailabilityService {

    private final StringRedisTemplate redisTemplate;
    private final RedisProperties properties;

    public RedisAvailabilityServiceImpl(StringRedisTemplate redisTemplate, RedisProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return getStatus() == RedisStatus.UP;
    }

    @Override
    public RedisStatus getStatus() {
        try {
            String response = redisTemplate.execute((RedisCallback<String>) conn -> conn.ping());
            if ("PONG".equalsIgnoreCase(response)) {
                return RedisStatus.UP;
            }
            log.warn("Redis PING returned unexpected response: '{}'", response);
            return RedisStatus.DEGRADED;
        } catch (Exception e) {
            log.warn("Redis availability check failed: {}", e.getMessage());
            return RedisStatus.DOWN;
        }
    }

    @Override
    public long getPingLatencyMs() {
        long start = System.nanoTime();
        try {
            redisTemplate.execute((RedisCallback<String>) conn -> conn.ping());
            return (System.nanoTime() - start) / 1_000_000L;
        } catch (Exception e) {
            return -1L;
        }
    }

    @Override
    public String getHost() {
        return properties.getHost();
    }

    @Override
    public int getPort() {
        return properties.getPort();
    }
}
