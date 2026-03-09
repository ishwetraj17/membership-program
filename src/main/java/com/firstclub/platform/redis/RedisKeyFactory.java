package com.firstclub.platform.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import static com.firstclub.platform.redis.RedisNamespaces.*;

/**
 * Centralised factory for all Redis key strings used by the FirstClub platform.
 *
 * <h3>Key format</h3>
 * <pre>{@code {env}:firstclub:{domain}:{subdomain}:{identifier...} }</pre>
 *
 * <p>All key strings are deterministic: supplying the same arguments always
 * produces the same key.  This class has no state beyond the fixed {@code env}
 * prefix derived from the active Spring profile at construction time.
 *
 * <p>This class is a pure string factory — it does not read from or write to
 * Redis.  It can be used freely in tests without a Redis connection.
 *
 * <p>Keys that map to planned-but-not-yet-active Redis usage are documented here
 * so the namespace is reserved and consistent with docs/performance/02-redis-usage.md.
 *
 * <p><b>Redis is never the source of truth for financial data.</b>
 * Every caller of these keys must implement a DB/source fallback.
 */
@Slf4j
@Component
public class RedisKeyFactory {

    /** e.g. "prod", "staging", "dev" — derived from active Spring profile. */
    private final String env;

    /** Short application identifier — always "firstclub". */
    private static final String APP_ID = APP;

    public RedisKeyFactory(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        this.env = (profiles.length > 0) ? profiles[0] : "dev";
        log.debug("RedisKeyFactory initialised with env prefix '{}'", this.env);
    }

    // ── Idempotency ────────────────────────────────────────────────────────

    /**
     * Key: cached response for an idempotency key.
     * Value: {@code {statusCode}|{responseBodyJson}}
     * TTL: {@code idempotency.redis.ttl-seconds} (default 86400 s / 24 h)
     * Fallback: {@code idempotency_keys} table in PostgreSQL.
     */
    public String idempotencyResponseKey(String merchantId, String idempotencyKey) {
        return build(IDEMPOTENCY, SUB_RESPONSE, merchantId, idempotencyKey);
    }

    /**
     * Key: in-flight execution lock for a client idempotency key.
     * Value: {@code {serverId}:{requestId}}
     * TTL: 30 s — prevents concurrent duplicate execution.
     * Fallback: DB UNIQUE constraint on idempotency_keys catches duplicates.
     */
    public String idempotencyLockKey(String merchantId, String idempotencyKey) {
        return build(IDEMPOTENCY, SUB_LOCK, merchantId, idempotencyKey);
    }

    // ── Rate Limiting ──────────────────────────────────────────────────────

    /**
     * Key: request-count counter for an API key in a given time window.
     * Value: integer (via INCR / EXPIRE).
     * TTL: equals the window duration.
     * Fallback: per-JVM counter (no cross-instance coordination when Redis is absent).
     *
     * @param merchantId  merchant UUID
     * @param keyPrefix   first 8 chars of the API key (never the full key)
     * @param window      one of {@link RedisNamespaces#WINDOW_1S}, {@link RedisNamespaces#WINDOW_1M}, …
     */
    public String rateLimitKey(String merchantId, String keyPrefix, String window) {
        return build(RATE_LIMIT, "apikey", merchantId, keyPrefix, window);
    }

    /**
     * Key: sliding-window counter for auth attempts from a single IP.
     * Pattern: {@code {env}:firstclub:rl:auth:ip:{ip}}
     */
    public String rateLimitAuthIpKey(String ip) {
        return build(RATE_LIMIT, "auth", "ip", ip);
    }

    /**
     * Key: sliding-window counter for auth attempts targeting a single email.
     * Pattern: {@code {env}:firstclub:rl:auth:user:{normalizedEmail}}
     *
     * @param normalizedEmail lower-cased, trimmed email address
     */
    public String rateLimitAuthEmailKey(String normalizedEmail) {
        return build(RATE_LIMIT, "auth", "user", normalizedEmail);
    }

    /**
     * Key: sliding-window counter for payment confirm attempts per merchant+customer.
     * Pattern: {@code {env}:firstclub:rl:payconfirm:{merchantId}:{customerId}}
     */
    public String rateLimitPayConfirmKey(String merchantId, String customerId) {
        return build(RATE_LIMIT, "payconfirm", merchantId, customerId);
    }

    /**
     * Key: sliding-window counter for inbound webhook events per provider+IP.
     * Pattern: {@code {env}:firstclub:rl:webhook:{provider}:{ip}}
     */
    public String rateLimitWebhookKey(String provider, String ip) {
        return build(RATE_LIMIT, "webhook", provider, ip);
    }

    /**
     * Key: sliding-window counter for merchant API key traffic.
     * Pattern: {@code {env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}}
     *
     * @param keyPrefix first 8 characters of the API key — never the full secret
     */
    public String rateLimitApiKeyKey(String merchantId, String keyPrefix) {
        return build(RATE_LIMIT, "apikey", merchantId, keyPrefix);
    }

    // ── Gateway Health ─────────────────────────────────────────────────────

    /**
     * Key: last-known health marker for a payment gateway.
     * Value: "UP" | "DOWN" | "DEGRADED"
     * TTL: 60 s — allow automated probes to evict stale markers quickly.
     * Fallback: {@code gateway_health_snapshots} table.
     */
    public String gatewayHealthKey(String gatewayName) {
        return build(GATEWAY, SUB_HEALTH, gatewayName.toUpperCase());
    }

    // ── Routing ────────────────────────────────────────────────────────────

    /**
     * Key: cached active routing rules for a merchant.
     * Value: JSON array of {@code RoutingRule} DTOs.
     * TTL: 300 s (5 min) — routing rules rarely change mid-day.
     * Fallback: {@code routing_rules} table.
     */
    public String routingCacheKey(String merchantId) {
        return build(ROUTING, SUB_RULES, merchantId);
    }

    /**
     * Granular routing-rule cache key scoped to a specific merchant (or global platform defaults)
     * and the exact routing discriminators used during gateway selection.
     *
     * <p>Pattern: {@code {env}:firstclub:routing:{scope}:{methodType}:{currency}:{retryNumber}}
     *
     * <p>Where {@code scope} is either the string representation of the merchant-account ID
     * (e.g. {@code "42"}) or the literal {@code "global"} for platform-wide defaults.
     *
     * <p>Value: JSON array of {@code GatewayRouteRuleResponseDTO}.
     * TTL: 300 s (5 min).
     * Fallback: {@code gateway_route_rules} table via {@code GatewayRouteRuleRepository}.
     *
     * @param scope        merchant-id string or {@code "global"}
     * @param methodType   payment method type (e.g. {@code "CARD"}, {@code "UPI"})
     * @param currency     ISO-4217 currency code (e.g. {@code "INR"}, {@code "USD"})
     * @param retryNumber  attempt number (1-based)
     */
    public String routingRuleCacheKey(String scope, String methodType, String currency, int retryNumber) {
        return build(ROUTING, scope, methodType.toLowerCase(), currency.toLowerCase(),
                String.valueOf(retryNumber));
    }

    // ── Merchant Settings ──────────────────────────────────────────────────

    /**
     * Key: cached merchant settings.
     * Value: JSON-serialised {@code MerchantSettingsDTO}.
     * TTL: 600 s (10 min).
     * Fallback: {@code merchant_settings} table.
     */
    public String merchantSettingsKey(String merchantId) {
        return build(MERCHANT, SUB_SETTINGS, merchantId);
    }

    // ── Feature Flags ──────────────────────────────────────────────────────

    /**
     * Key: feature flag current value.
     * Value: "true" | "false"
     * TTL: 60 s — feature flags should propagate quickly.
     * Fallback: {@code feature_flags} table.
     */
    public String featureFlagKey(String flagKey) {
        return build(FEATURE_FLAG, flagKey);
    }

    // ── Projection Lag ─────────────────────────────────────────────────────

    /**
     * Key: marker tracking whether a named projection is stale.
     * Value: ISO-8601 timestamp of last successful rebuild.
     * TTL: 120 s — refreshed by the projection scheduler on each run.
     * Fallback: query source tables directly.
     */
    public String projectionLagMarkerKey(String projectionName) {
        return build(PROJECTION, "lag", projectionName);
    }

    // ── Ops Projection Hot-Cache ───────────────────────────────────────────

    /**
     * Hot-cache key for a single subscription status projection row.
     * Pattern: {@code {env}:firstclub:proj:sub-status:{merchantId}:{subscriptionId}}
     * TTL: 120 s — stale values corrected by next event or rebuild.
     * Fallback: {@code subscription_status_projection} table.
     */
    public String projSubStatusKey(String merchantId, String subscriptionId) {
        return build(PROJECTION, "sub-status", merchantId, subscriptionId);
    }

    /**
     * Hot-cache key for a single invoice summary projection row.
     * Pattern: {@code {env}:firstclub:proj:invoice-summary:{merchantId}:{invoiceId}}
     * TTL: 120 s.
     * Fallback: {@code invoice_summary_projection} table.
     */
    public String projInvoiceSummaryKey(String merchantId, String invoiceId) {
        return build(PROJECTION, "invoice-summary", merchantId, invoiceId);
    }

    /**
     * Hot-cache key for a single payment summary projection row.
     * Pattern: {@code {env}:firstclub:proj:payment-summary:{merchantId}:{paymentIntentId}}
     * TTL: 120 s.
     * Fallback: {@code payment_summary_projection} table.
     */
    public String projPaymentSummaryKey(String merchantId, String paymentIntentId) {
        return build(PROJECTION, "payment-summary", merchantId, paymentIntentId);
    }

    // ── Deduplication ──────────────────────────────────────────────────────

    /**
     * Key: short-window dedup marker for a business fingerprint.
     * Value: "1" (presence indicates already-processed)
     * TTL: domain-specific (typically 60–3600 s).
     * Fallback: UNIQUE constraint on the relevant table's {@code business_fingerprint} column.
     *
     * @param domain      e.g. "payment", "refund"
     * @param fingerprint SHA-256 of the business-unique combination
     */
    public String dedupFingerprintKey(String domain, String fingerprint) {
        return build(DEDUP, domain, fingerprint);
    }

    /**
     * Key: Redis fast-path dedup marker for an inbound webhook by provider + event ID.
     * Pattern: {@code {env}:firstclub:dedup:webhook:{provider}:{eventId}}
     * Value: "1" (presence = already queued or processed)
     * TTL: {@link com.firstclub.payments.webhooks.WebhookDedupService#EVENT_ID_TTL_SECONDS} (3600 s).
     * Fallback: {@code webhook_events.event_id} UNIQUE constraint.
     */
    public String webhookEventDedupKey(String provider, String eventId) {
        return build(DEDUP, WEBHOOK, provider, eventId);
    }

    /**
     * Key: Redis payload-hash dedup marker (fallback when event-ID is absent/rotated).
     * Pattern: {@code {env}:firstclub:dedup:webhookfp:{provider}:{payloadHash}}
     * Value: "1"
     * TTL: {@link com.firstclub.payments.webhooks.WebhookDedupService#PAYLOAD_HASH_TTL_SECONDS} (300 s).
     * Fallback: none — this is a best-effort secondary guard only.
     */
    public String webhookPayloadFingerprintKey(String provider, String payloadHash) {
        return build(DEDUP, "webhookfp", provider, payloadHash);
    }

    /**
     * Key: durable two-tier dedup marker for a critical business effect.
     * Pattern: {@code {env}:firstclub:dedup:biz:{effectType}:{fingerprint}}
     * Value: "1"
     * TTL: {@link com.firstclub.platform.dedup.BusinessEffectDedupService#TTL_SECONDS} (86400 s).
     * Fallback: {@code business_effect_fingerprints} UNIQUE (effect_type, fingerprint).
     */
    public String bizEffectDedupKey(String effectType, String fingerprint) {
        return build(DEDUP, SUB_BIZ, effectType.toLowerCase(), fingerprint);
    }

    // ── Outbox ─────────────────────────────────────────────────────────────

    /**
     * Key: marker for an event that has been successfully dispatched.
     * Value: "1"
     * TTL: 3600 s — short-circuit repeated polling for recently processed events.
     * Fallback: {@code outbox_events.status = PROCESSED} in the DB.
     */
    public String outboxProcessedKey(String eventId) {
        return build(OUTBOX, SUB_PROCESSED, eventId);
    }

    /**
     * Key: distributed processing lock for a single outbox event.
     * Pattern: {@code {env}:firstclub:outbox:proc:{eventId}}
     * Value: {@code {owner}} (hostname:pid)
     * TTL: 60 s — prevents two pollers from processing the same event concurrently.
     * Fallback: {@code processing_owner} column on {@code outbox_events}.
     */
    public String outboxProcLockKey(String eventId) {
        return build(OUTBOX, SUB_PROC, eventId);
    }

    // ── Scheduler Locks ────────────────────────────────────────────────────

    /**
     * Key: distributed mutex for a named scheduled job.
     * Value: {@code {serverId}:{acquiredAt}}
     * TTL: job-specific (e.g. 3600 s for nightly jobs).
     * Fallback: {@code job_locks} table in PostgreSQL (always active).
     *
     * <p>Note: PostgreSQL {@code job_locks} is the authoritative lock.
     * Redis provides a fast pre-check to reduce DB contention.
     */
    public String schedulerLockKey(String jobName) {
        return build(SCHEDULER, SUB_LOCK, jobName);
    }

    // ── Timeline Cache ─────────────────────────────────────────────────────

    /**
     * Key: paginated timeline cache for a domain entity.
     * Value: JSON array of timeline events.
     * TTL: 300 s.
     * Fallback: query {@code domain_events} table.
     */
    public String timelineCacheKey(String entityType, String entityId) {
        return build(TIMELINE, entityType, entityId);
    }

    /**
     * Merchant-scoped hot-cache key for the ops timeline of a specific entity.
     * Pattern: {@code {env}:firstclub:timeline:{merchantId}:{entityType}:{entityId}}
     * Value: JSON array of {@code TimelineEventDTO}.
     * TTL: {@value com.firstclub.reporting.ops.timeline.cache.TimelineCacheService#TTL_SECONDS} s.
     * Fallback: {@code ops_timeline_events} table.
     *
     * @param entityType lower-cased entity type constant, e.g. {@code "subscription"}
     */
    public String opsTimelineKey(String merchantId, String entityType, String entityId) {
        return build(TIMELINE, merchantId, entityType.toLowerCase(), entityId);
    }

    // ── Subscription ──────────────────────────────────────────────────────

    /**
     * Key: optimistic-lock conflict prevention for subscription writes.
     * Value: {@code {requestId}}
     * TTL: 5 s.
     * Fallback: {@code @Version} optimistic lock on {@code subscriptions_v2}.
     */
    public String subscriptionLockKey(String subscriptionId) {
        return build(SUBSCRIPTION, SUB_LOCK, subscriptionId);
    }

    /**
     * Key: cached count of active subscriptions for a merchant dashboard.
     * Value: integer string.
     * TTL: 300 s.
     * Fallback: {@code COUNT(*) FROM subscriptions_v2 WHERE merchant_id=? AND status='ACTIVE'}.
     */
    public String subscriptionActiveCountKey(String merchantId) {
        return build(SUBSCRIPTION, "active", "count", merchantId);
    }

    // ── Payment ────────────────────────────────────────────────────────────

    /**
     * Key: exclusive lock during payment capture to prevent concurrent confirmation.
     * Value: {@code {requestId}}
     * TTL: 10 s.
     * Fallback: {@code FOR UPDATE} on {@code payment_intents_v2}.
     */
    public String paymentCaptureLockKey(String paymentIntentId) {
        return build(PAYMENT, "capture", SUB_LOCK, paymentIntentId);
    }

    // ── Refund ─────────────────────────────────────────────────────────────

    /**
     * Key: exclusive lock during refund creation against a payment intent.
     * Value: {@code {requestId}}
     * TTL: 10 s.
     * Fallback: {@code SELECT FOR UPDATE} on {@code payment_intents_v2}.
     */
    public String refundLockKey(String paymentIntentId) {
        return build(REFUND, SUB_LOCK, paymentIntentId);
    }

    // ── Dispute ────────────────────────────────────────────────────────────

    /**
     * Key: exclusive lock during dispute open/resolve against a payment.
     * Value: {@code {requestId}}
     * TTL: 10 s.
     * Fallback: {@code SELECT FOR UPDATE} on {@code payments}.
     */
    public String disputeLockKey(String paymentId) {
        return build(DISPUTE, SUB_LOCK, paymentId);
    }

    // ── Risk ───────────────────────────────────────────────────────────────

    /**
     * Key: IP block-list entry.
     * Value: {@code {addedBy}:{addedAt}}
     * TTL: none (persists until explicitly deleted).
     * Fallback: {@code ip_block_list} table.
     */
    public String riskIpBlockKey(String ipAddress) {
        return build(RISK, "ipblock", ipAddress);
    }

    /**
     * Key: payment velocity counter for a customer in a given window.
     * Value: integer (via INCR / EXPIRE).
     * TTL: equals the window duration.
     * Fallback: {@code COUNT(*) FROM payment_intents_v2 WHERE customer_id=? AND created_at>?}.
     */
    public String riskVelocityKey(String merchantId, String customerId, String window) {
        return build(RISK, "velocity", merchantId, customerId, window);
    }

    // ── Webhook ────────────────────────────────────────────────────────────

    /**
     * Key: per-event-per-endpoint delivery lock to prevent duplicate dispatch.
     * Value: {@code {deliveryAttemptId}}
     * TTL: 30 s.
     * Fallback: {@code FOR UPDATE SKIP LOCKED} on {@code webhook_delivery_attempts}.
     */
    public String webhookDeliveryLockKey(String eventId, String endpointId) {
        return build(WEBHOOK, SUB_DELIVERY, SUB_LOCK, eventId, endpointId);
    }

    /**
     * Key: disabled marker for a webhook endpoint after too many failures.
     * Value: "1"
     * TTL: none (removed by operator re-enable).
     * Fallback: {@code webhook_endpoints.is_active} column.
     */
    public String webhookEndpointDisabledKey(String endpointId) {
        return build(WEBHOOK, "endpoint", SUB_DISABLED, endpointId);
    }

    /**
     * Key: distributed processing lock for a single outbound webhook delivery row.
     * Pattern: {@code {env}:firstclub:webhook:lock:delivery:{deliveryId}}
     * Value: {@code processingOwner} ({@code hostname:pid}) of the JVM that claimed the row.
     * TTL: 30 s — prevents concurrent re-dispatch of the same delivery row.
     * Fallback: {@code FOR UPDATE SKIP LOCKED} on {@code merchant_webhook_deliveries}.
     *
     * @param deliveryId the primary-key ID of the {@code merchant_webhook_deliveries} row
     */
    public String webhookDeliveryProcessLockKey(String deliveryId) {
        return build(WEBHOOK, SUB_LOCK, SUB_DELIVERY, deliveryId);
    }

    // ── Ledger ─────────────────────────────────────────────────────────

    /**
     * Key: cached account balance as of a specific date.
     * Value: decimal string.
     * TTL: 3600 s.
     * Fallback: {@code SUM(ledger_lines)} aggregate query.
     */
    public String ledgerBalanceCacheKey(String accountId, String date) {
        return build(LEDGER, SUB_BALANCE, accountId, date);
    }

    // ── Reconciliation ─────────────────────────────────────────────────────

    /**
     * Key: cached reconciliation result for a specific date.
     * Value: JSON-serialised {@code ReconBatchResult}.
     * TTL: 7200 s (2 h).
     * Fallback: {@code recon_batches} table.
     */
    public String reconResultCacheKey(String date) {
        return build(RECON, SUB_RESULT, date);
    }

    /**
     * Key: distributed lock for the daily reconciliation job.
     * Value: {@code {serverId}}
     * TTL: 3600 s (1 h).
     * Fallback: {@code job_locks} table.
     */
    public String reconJobLockKey(String date) {
        return build(RECON, SUB_LOCK, date);
    }

    // ── Search (Phase 13) ──────────────────────────────────────────────────
    /**
     * Key: short-lived cache for a unified admin search result page.
     * Pattern: {@code {env}:firstclub:search:{merchantId}:{queryHash}}
     * Value: JSON array of {@code SearchResultDTO}.
     * TTL: {@value com.firstclub.admin.search.cache.SearchCacheService#TTL_SECO
NDS} s.                                                                              * Fallback: live DB queries across all entity repos.
     *
     * @param merchantId  tenant isolator (string form)
     * @param queryHash   SHA-256 hex digest of {@code queryType:queryValue}
     */
    public String searchKey(String merchantId, String queryHash) {
        return build(SEARCH, merchantId, queryHash);
    }

    // ── Phase 2: Distributed Locks & Fencing ──────────────────────────────────

    /**
     * Generic entity-scoped distributed lock key.
     * Pattern: {@code {env}:firstclub:lock:{entityType}:{entityId}}
     * Example: {@code prod:firstclub:lock:subscription:991}
     * Value: {@code {ownerId}} (hostname + thread or requestId).
     * TTL: {@code app.redis.ttl.distributed-lock} (default 30 s).
     * Fallback: {@code SELECT FOR UPDATE} on the target row.
     *
     * @param entityType  domain entity name, lowercase (e.g. {@code "subscription"}, {@code "payment"})
     * @param entityId    string form of the entity primary key
     */
    public String distributedLockKey(String entityType, String entityId) {
        return build(LOCK, entityType, entityId);
    }

    /**
     * Optimistic-concurrency fence token key.
     * Pattern: {@code {env}:firstclub:fence:{entityType}:{entityId}}
     * Example: {@code prod:firstclub:fence:subscription:991}
     * Value: monotonically incrementing integer (via INCR).
     * TTL: none — fence tokens accumulate until the entity is deleted.
     * Fallback: {@code version} column in the entity table (optimistic locking).
     *
     * @param entityType  domain entity name, lowercase
     * @param entityId    string form of the entity primary key
     */
    public String fenceKey(String entityType, String entityId) {
        return build(FENCE, entityType, entityId);
    }

    /**
     * Alias for {@link #fenceKey} using the Phase 6 distributed-lock naming convention.
     * Callers in the {@code com.firstclub.platform.lock} package use this name.
     */
    public String fenceTokenKey(String resourceType, String resourceId) {
        return fenceKey(resourceType, resourceId);
    }

    // ── Phase 2: Endpoint-Scoped Rate Limiting ────────────────────────────────

    /**
     * Per-merchant, per-endpoint rate limit counter.
     * Pattern: {@code {env}:firstclub:rl:{merchantId}:endpoint:{endpointSlug}}
     * Example: {@code prod:firstclub:rl:merchant:42:endpoint:payment_capture}
     * Value: integer counter (via INCR / EXPIRE).
     * TTL: {@code app.redis.ttl.rate-limit-window} (default 60 s).
     * Fallback: per-JVM counter (no cross-instance coordination when Redis is absent).
     *
     * @param merchantId    merchant identifier
     * @param endpointSlug  lowercased, underscore-separated endpoint name
     */
    public String rateLimitEndpointKey(String merchantId, String endpointSlug) {
        return build(RATE_LIMIT, merchantId, SUB_ENDPOINT, endpointSlug);
    }

    // ── Phase 2: Worker Leases ────────────────────────────────────────────────

    /**
     * Outbox worker lease marker for a single domain event.
     * Pattern: {@code {env}:firstclub:worker:outbox:lease:event:{eventId}}
     * Example: {@code prod:firstclub:worker:outbox:lease:event:12345}
     * Value: {@code {workerId}} (hostname + thread).
     * TTL: {@code app.redis.ttl.worker-lease} (default 60 s).
     * Fallback: {@code outbox_events.locked_until} column.
     *
     * @param eventId  string form of the outbox event primary key
     */
    public String workerLeaseKey(String eventId) {
        return build(WORKER, OUTBOX, SUB_LEASE, SUB_EVENT, eventId);
    }

    // ── Phase 2: Hot Projection Cache ─────────────────────────────────────────

    /**
     * Hot-cache key for a merchant KPI/projection result for a given date.
     * Pattern: {@code {env}:firstclub:cache:projection:{merchantId}:kpi:{date}}
     * Example: {@code prod:firstclub:cache:projection:merchant:42:kpi:2026-03-09}
     * Value: JSON-serialised KPI summary DTO.
     * TTL: {@code app.redis.ttl.projection-cache} (default 10 m).
     * Fallback: live aggregate from {@code subscription_status_projection}.
     *
     * @param merchantId  merchant identifier
     * @param date        ISO-8601 date string (e.g. {@code "2026-03-09"})
     */
    public String projectionKpiCacheKey(String merchantId, String date) {
        return build(CACHE, "projection", merchantId, "kpi", date);
    }



    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Returns the current environment prefix (e.g., "prod", "staging", "dev").
     */
    public String getEnv() {
        return env;
    }

    // ── Key assembler ──────────────────────────────────────────────────────

    /**
     * Assembles a colon-delimited Redis key with the canonical prefix.
     *
     * <p>Calling {@code build("idem", "resp", "merchant1", "key1")} with
     * env="prod" produces: {@code "prod:firstclub:idem:resp:merchant1:key1"}.
     */
    String build(String... segments) {
        StringBuilder sb = new StringBuilder(env).append(SEP).append(APP_ID);
        for (String seg : segments) {
            sb.append(SEP).append(seg);
        }
        return sb.toString();
    }
}
