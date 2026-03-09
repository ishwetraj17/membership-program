package com.firstclub.platform.redis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration properties for the Redis infrastructure layer.
 *
 * <p>All properties are prefixed with {@code app.redis}. Spring Boot binds them
 * via constructor or setter injection before any Redis bean is created.
 *
 * <h3>Minimal required configuration when Redis is enabled</h3>
 * <pre>{@code
 * app.redis.enabled=true
 * app.redis.host=redis-hostname
 * app.redis.port=6379
 * }</pre>
 *
 * <p>When {@code app.redis.enabled=false} (the default) the entire Redis stack
 * is skipped; {@link RedisConfig} is not loaded and no connection is attempted.
 *
 * <p><b>Redis is never the source of truth for financial data.</b>  Every cached
 * value has a fallback to PostgreSQL.  See docs/performance/02-redis-usage.md.
 */
@ConfigurationProperties(prefix = "app.redis")
@Getter
@Setter
public class RedisProperties {

    /**
     * Set to {@code true} to enable Redis. When {@code false} the entire
     * platform.redis package is inert and all reads fall back to PostgreSQL.
     */
    private boolean enabled = false;

    /** Redis server hostname or IP. */
    private String host = "localhost";

    /** Redis server port. Default 6379. */
    private int port = 6379;

    /**
     * Optional password / AUTH token. Leave empty or omit for no-auth
     * development instances. In production supply via environment variable.
     */
    private String password = "";

    /** Whether to use TLS when connecting to Redis. Recommended in production. */
    private boolean ssl = false;

    /** Redis logical database index (0–15). Default 0. */
    private int database = 0;

    /**
     * Short application prefix embedded in every key.
     * Default {@code fc} (FirstClub).
     * Changing this prefix invalidates all existing keys.
     */
    private String keyPrefix = "fc";

    /**
     * Default TTL in seconds applied to any key that does not specify its own
     * TTL.  Exists as a safety net to prevent unbounded key accumulation.
     * Default 300 s (5 minutes).
     */
    private int defaultTtlSeconds = 300;

    /**
     * Timeout in milliseconds for the initial Redis connection attempt.
     * The application will start in degraded mode if this timeout elapses.
     */
    private int connectTimeoutMs = 2000;

    /**
     * Timeout in milliseconds for individual Redis commands (PING, GET, SET …).
     */
    private int commandTimeoutMs = 500;
}
