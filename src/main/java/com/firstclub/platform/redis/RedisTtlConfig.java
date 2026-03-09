package com.firstclub.platform.redis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed TTL configuration for all Redis key domains.
 *
 * <p>Properties are bound from the {@code app.redis.ttl} namespace.
 * Default values represent safe, battle-tested TTLs for each domain.
 * Override in {@code application-prod.properties} or environment variables.
 *
 * <h3>Binding example</h3>
 * <pre>{@code
 * app.redis.ttl.idempotency-lock=PT30S
 * app.redis.ttl.idempotency-record=PT24H
 * app.redis.ttl.rate-limit-window=PT60S
 * app.redis.ttl.routing-cache=PT5M
 * app.redis.ttl.projection-cache=PT10M
 * app.redis.ttl.distributed-lock=PT30S
 * app.redis.ttl.search-cache=PT5M
 * app.redis.ttl.worker-lease=PT60S
 * }</pre>
 *
 * <p>Spring Boot binds ISO-8601 duration strings automatically.
 * Use {@code PT} prefix for time-based durations: {@code PT30S} = 30 seconds,
 * {@code PT5M} = 5 minutes, {@code PT24H} = 24 hours.
 *
 * <p><b>Do not set TTLs to zero or negative values in production.</b>
 * A zero TTL causes immediate expiry; a negative TTL causes key persistence.
 * Both are defects. Minimum recommended value: 1 second.
 *
 * <p><b>Redis is never the source of truth for financial data.</b>
 * Every cached value has a fallback to PostgreSQL. These TTLs control
 * cache freshness, not data durability.
 *
 * @see RedisKeyFactory
 * @see RedisOpsFacade
 */
@ConfigurationProperties(prefix = "app.redis.ttl")
@Getter
@Setter
public class RedisTtlConfig {

    /**
     * TTL for the idempotency in-flight lock.
     * Prevents concurrent duplicate execution of the same request.
     * Once expired, a fresh execution is permitted.
     * Default: 30 seconds.
     */
    private Duration idempotencyLock = Duration.ofSeconds(30);

    /**
     * TTL for the idempotency cached response.
     * Allows repeated identical requests to receive the same response
     * within the replay window.
     * Default: 24 hours.
     */
    private Duration idempotencyRecord = Duration.ofHours(24);

    /**
     * TTL for individual rate-limit counter windows.
     * Applies to per-merchant-per-endpoint and IP-based counters.
     * Default: 60 seconds.
     */
    private Duration rateLimitWindow = Duration.ofSeconds(60);

    /**
     * TTL for routing rules cache entries.
     * Routing rules rarely change mid-day; a 5-minute TTL provides
     * freshness with low Redis read pressure.
     * Default: 5 minutes.
     */
    private Duration routingCache = Duration.ofMinutes(5);

    /**
     * TTL for projection / read-model hot-cache entries.
     * Short-lived to ensure stale projections are refreshed frequently.
     * Default: 10 minutes.
     */
    private Duration projectionCache = Duration.ofMinutes(10);

    /**
     * TTL for general distributed (entity-scoped) locks.
     * Balances protection against abandoned locks vs holding locks too long.
     * Default: 30 seconds.
     */
    private Duration distributedLock = Duration.ofSeconds(30);

    /**
     * TTL for unified search result cache entries.
     * Short-lived since search results change when underlying data changes.
     * Default: 5 minutes.
     */
    private Duration searchCache = Duration.ofMinutes(5);

    /**
     * TTL for async worker (outbox processor) lease markers.
     * After this TTL, a competing worker may claim the event.
     * Must be longer than typical event processing time.
     * Default: 60 seconds.
     */
    private Duration workerLease = Duration.ofSeconds(60);
}
