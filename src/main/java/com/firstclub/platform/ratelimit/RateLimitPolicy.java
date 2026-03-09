package com.firstclub.platform.ratelimit;

import java.time.Duration;

/**
 * Well-known rate limit policies applied at the platform level.
 *
 * <p>Each policy carries a <em>key segment</em> (used in the Redis key),
 * a <em>default limit</em> (requests per window), and a <em>default window
 * duration</em>.  Both limit and window are overridable via
 * {@link RateLimitProperties} config entries.
 *
 * <h3>Algorithm</h3>
 * All policies use a <strong>sliding window log</strong> implemented with a
 * Redis sorted set.  For each key, a Lua script atomically:
 * <ol>
 *   <li>Removes set members older than {@code now - windowMs}</li>
 *   <li>Counts remaining members</li>
 *   <li>If {@code count < limit}: adds a new member; returns ALLOWED + remaining</li>
 *   <li>If {@code count >= limit}: returns DENIED + epoch-ms when the oldest
 *       member ages out (= reset time)</li>
 * </ol>
 * This is more precise than fixed-window counters: it has no boundary bursts
 * and the reset time accurately reflects when the next slot opens.
 *
 * <h3>Defaults</h3>
 * <pre>
 * AUTH_BY_IP       — 20  req / 5  min  (login + register)
 * AUTH_BY_EMAIL    — 10  req / 15 min  (login only)
 * PAYMENT_CONFIRM  — 10  req / 10 min  (per merchant × customer)
 * WEBHOOK_INGEST   — 200 req / 1  min  (per provider × IP)
 * APIKEY_GENERAL   — 1000 req / 1  min (per merchant × key prefix)
 * </pre>
 */
public enum RateLimitPolicy {

    /** Protects authentication endpoints from brute-force by client IP. */
    AUTH_BY_IP("auth:ip", 20, Duration.ofMinutes(5)),

    /** Protects per-account credential guessing (email enumeration / stuffing). */
    AUTH_BY_EMAIL("auth:user", 10, Duration.ofMinutes(15)),

    /**
     * Protects payment confirmation from card-testing attacks.
     * Subject: {@code {merchantId}:{customerId}}.
     */
    PAYMENT_CONFIRM("payconfirm", 10, Duration.ofMinutes(10)),

    /**
     * Dampens webhook storm from upstream payment providers.
     * Subject: {@code {provider}:{ip}}.
     */
    WEBHOOK_INGEST("webhook", 200, Duration.ofMinutes(1)),

    /** General API-key traffic shaping per merchant tier. */
    APIKEY_GENERAL("apikey", 1000, Duration.ofMinutes(1));

    /** Redis key segment — placed after {@code rl:} prefix. */
    private final String keySegment;
    /** Default request limit per window. */
    private final int defaultLimit;
    /** Default sliding window duration. */
    private final Duration defaultWindow;

    RateLimitPolicy(String keySegment, int defaultLimit, Duration defaultWindow) {
        this.keySegment = keySegment;
        this.defaultLimit = defaultLimit;
        this.defaultWindow = defaultWindow;
    }

    public String getKeySegment() { return keySegment; }
    public int getDefaultLimit()  { return defaultLimit; }
    public Duration getDefaultWindow() { return defaultWindow; }
    public long getDefaultWindowMs()   { return defaultWindow.toMillis(); }
}
