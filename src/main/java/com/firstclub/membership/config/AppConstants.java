package com.firstclub.membership.config;

/**
 * Application-wide named constants.
 *
 * Centralises magic numbers so that related configuration in
 * application.properties, @Scheduled annotations, and @RequestParam defaults
 * all refer to a single source of truth.
 *
 * All fields are {@code public static final} so they can be used in annotation
 * parameters (which require compile-time constant expressions).
 */
public final class AppConstants {

    private AppConstants() {
        // utility class — no instances
    }

    // -------------------------------------------------------------------------
    // Pagination defaults
    // -------------------------------------------------------------------------

    /** Default page size for paginated API endpoints. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    // -------------------------------------------------------------------------
    // JWT token lifetimes
    // -------------------------------------------------------------------------

    /** Access-token lifetime: 24 hours in milliseconds. */
    public static final long JWT_EXPIRATION_MS = 86_400_000L;

    /** Refresh-token lifetime: 7 days in milliseconds. */
    public static final long JWT_REFRESH_EXPIRATION_MS = 604_800_000L;

    // -------------------------------------------------------------------------
    // Token blacklist scheduler
    // -------------------------------------------------------------------------

    /** Interval at which expired blacklist entries are evicted: 10 minutes in ms. */
    public static final long TOKEN_CLEANUP_INTERVAL_MS = 600_000L;

    // -------------------------------------------------------------------------
    // HikariCP connection-pool tuning
    // -------------------------------------------------------------------------

    /** Max time (ms) to wait for a connection from the pool before throwing: 30 s. */
    public static final int HIKARI_CONNECTION_TIMEOUT_MS = 30_000;

    /** Max time (ms) a connection may sit idle before being retired: 10 min. */
    public static final int HIKARI_IDLE_TIMEOUT_MS = 600_000;

    /** Max lifetime (ms) of a connection in the pool before forced eviction: 30 min. */
    public static final int HIKARI_MAX_LIFETIME_MS = 1_800_000;

    // -------------------------------------------------------------------------
    // Subscription batch processing
    // -------------------------------------------------------------------------

    /** Maximum number of subscriptions processed in a single renewal batch. */
    public static final int RENEWAL_BATCH_SIZE = 200;
}
