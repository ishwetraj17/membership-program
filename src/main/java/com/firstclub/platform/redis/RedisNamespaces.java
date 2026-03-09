package com.firstclub.platform.redis;

/**
 * Canonical namespace segment constants used by {@link RedisKeyFactory}.
 *
 * <h3>Key format</h3>
 * <pre>{@code {env}:firstclub:{domain}:{subdomain}:{identifier...} }</pre>
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * prod:firstclub:idem:resp:merchantId:keyHash          → idempotency cached response
 * prod:firstclub:idem:lock:merchantId:keyHash          → idempotency in-flight lock
 * prod:firstclub:rl:apikey:merchantId:prefix:1m        → per-key rate limit (1-minute window)
 * prod:firstclub:gw:health:RAZORPAY                    → gateway health marker
 * prod:firstclub:routing:rules:merchantId              → routing rules cache
 * prod:firstclub:merchant:settings:merchantId          → merchant settings cache
 * prod:firstclub:flag:paymentV2Enabled                 → feature flag value
 * prod:firstclub:proj:lag:subscription_status          → projection lag marker
 * prod:firstclub:dedup:payment:fingerprint             → payment dedup fingerprint
 * prod:firstclub:outbox:processed:eventId              → outbox event processed marker
 * prod:firstclub:scheduler:lock:dunning_daily          → scheduler mutex
 * prod:firstclub:timeline:subscription:subId           → timeline cache
 * }</pre>
 *
 * <p>All constants in this class are {@code public static final} so they can be
 * used directly in key-generation logic or documented in metrics/logging without
 * any runtime overhead.
 *
 * <p><b>Naming convention:</b> use lowercase letters and underscores only.
 * Colons are the namespace separator and must not appear inside a segment.
 */
public final class RedisNamespaces {

    /** Fixed application identifier embedded in every key. Never changes. */
    public static final String APP = "firstclub";

    // ── Domain segments ────────────────────────────────────────────────────

    /** Idempotency layer — response cache and in-flight locks. */
    public static final String IDEMPOTENCY        = "idem";

    /** Rate limiting counters — keyed by entity and time window. */
    public static final String RATE_LIMIT         = "rl";

    /** Gateway health and availability markers. */
    public static final String GATEWAY            = "gw";

    /** Payment routing rules cache. */
    public static final String ROUTING            = "routing";

    /** Merchant-level settings and configuration cache. */
    public static final String MERCHANT           = "merchant";

    /** Feature flags (on/off switches). */
    public static final String FEATURE_FLAG       = "flag";

    /** Read-model projection lag and rebuild markers. */
    public static final String PROJECTION         = "proj";

    /** Deduplication fingerprints. */
    public static final String DEDUP              = "dedup";

    /** Outbox event processing markers. */
    public static final String OUTBOX             = "outbox";

    /** Distributed scheduler / job locks. */
    public static final String SCHEDULER          = "scheduler";

    /** Timeline cache for domain entities. */
    public static final String TIMELINE           = "timeline";

    /** Subscription-domain keys (state cache, active count). */
    public static final String SUBSCRIPTION       = "sub";

    /** Payment-domain keys (capture locks, intent dedup). */
    public static final String PAYMENT            = "payment";

    /** Refund-domain keys (per-payment refund lock). */
    public static final String REFUND             = "refund";

    /** Dispute-domain keys (per-payment dispute processing lock). */
    public static final String DISPUTE            = "dispute";

    /** Risk-domain keys (IP blocks, velocity counters). */
    public static final String RISK               = "risk";

    /** Webhook delivery locks and endpoint disable markers. */
    public static final String WEBHOOK            = "webhook";

    /** Ledger balance snapshot cache. */
    public static final String LEDGER             = "ledger";

    /** Reconciliation result cache and job lock. */
    public static final String RECON              = "recon";

    /** Unified admin search result cache (Phase 13). */
    public static final String SEARCH             = "search";

    /**
     * Distributed lock domain — entity-scoped mutex keys.
     * Maps to keys like {@code {env}:firstclub:lock:{entityType}:{entityId}}.
     * Distinct from {@link #SUB_LOCK} which is a sub-domain suffix.
     */
    public static final String LOCK               = "lock";

    /**
     * Optimistic-concurrency fence token domain.
     * Maps to keys like {@code {env}:firstclub:fence:{entityType}:{entityId}}.
     * Used to detect stale write attempts via incrementing fence counters.
     */
    public static final String FENCE              = "fence";

    /**
     * Async worker / outbox processor lease domain.
     * Maps to keys like {@code {env}:firstclub:worker:{domain}:lease:{entityId}}.
     */
    public static final String WORKER             = "worker";

    /**
     * Generic hot-cache domain for projection / KPI results.
     * Maps to keys like {@code {env}:firstclub:cache:projection:{entityId}:kpi:{date}}.
     */
    public static final String CACHE              = "cache";

    // ── Sub-domain segments ────────────────────────────────────────────────

    public static final String SUB_RESPONSE       = "resp";
    public static final String SUB_LOCK           = "lock";
    public static final String SUB_HEALTH         = "health";
    public static final String SUB_RULES          = "rules";
    public static final String SUB_SETTINGS       = "settings";
    public static final String SUB_BALANCE        = "balance";
    public static final String SUB_PROCESSED      = "processed";
    public static final String SUB_PROC           = "proc";
    public static final String SUB_DISABLED       = "disabled";
    public static final String SUB_RESULT         = "result";
    public static final String SUB_DELIVERY       = "delivery";
    public static final String SUB_SNAPSHOT       = "snapshot";

    /** Sub-domain for webhook event-id dedup keys. */
    public static final String SUB_EVENT          = "event";

    /** Sub-domain for webhook payload-fingerprint dedup keys. */
    public static final String SUB_FP             = "fp";

    /** Sub-domain for business-effect dedup keys. */
    public static final String SUB_BIZ            = "biz";

    /** Sub-domain for API-endpoint-scoped rate limit keys. */
    public static final String SUB_ENDPOINT       = "endpoint";

    /** Sub-domain for worker lease markers. */
    public static final String SUB_LEASE          = "lease";

    /** Sub-domain for search query-hash cache keys. */
    public static final String SUB_QHASH          = "qhash";

    // ── Rate-limit window labels ────────────────────────────────────────────

    public static final String WINDOW_1S          = "1s";
    public static final String WINDOW_1M          = "1m";
    public static final String WINDOW_1H          = "1h";
    public static final String WINDOW_24H         = "24h";

    // ── Separator used between key segments ────────────────────────────────

    public static final String SEP = ":";

    private RedisNamespaces() { /* constants-only utility class */ }
}
