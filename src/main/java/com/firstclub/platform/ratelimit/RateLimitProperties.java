package com.firstclub.platform.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Externally configurable overrides for rate limit policies.
 *
 * <h3>Example application.properties</h3>
 * <pre>
 * app.rate-limit.enabled=true
 * app.rate-limit.policies.AUTH_BY_IP.limit=50
 * app.rate-limit.policies.AUTH_BY_IP.window=PT5M
 * app.rate-limit.policies.WEBHOOK_INGEST.limit=500
 * app.rate-limit.policies.WEBHOOK_INGEST.window=PT1M
 * app.rate-limit.policies.APIKEY_GENERAL.limit=2000
 * app.rate-limit.policies.APIKEY_GENERAL.window=PT1M
 * </pre>
 *
 * <p>Any policy not configured here falls back to the defaults defined in
 * {@link RateLimitPolicy}.
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** Master on/off switch. Default {@code true}. */
    private boolean enabled = true;

    /**
     * Per-policy override map keyed by {@link RateLimitPolicy#name()}.
     * Any policy absent from the map uses its built-in defaults.
     */
    private Map<String, PolicyConfig> policies = new HashMap<>();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, PolicyConfig> getPolicies() { return policies; }
    public void setPolicies(Map<String, PolicyConfig> policies) { this.policies = policies; }

    /** Resolve effective limit for a policy, applying any configured override. */
    public int resolveLimit(RateLimitPolicy policy) {
        PolicyConfig cfg = policies.get(policy.name());
        return (cfg != null && cfg.getLimit() > 0) ? cfg.getLimit() : policy.getDefaultLimit();
    }

    /** Resolve effective window for a policy, applying any configured override. */
    public Duration resolveWindow(RateLimitPolicy policy) {
        PolicyConfig cfg = policies.get(policy.name());
        return (cfg != null && cfg.getWindow() != null) ? cfg.getWindow() : policy.getDefaultWindow();
    }

    // ── Nested config class ───────────────────────────────────────────────────

    public static class PolicyConfig {
        /** Maximum requests allowed in the window. */
        private int limit;
        /** Sliding window duration (ISO-8601 e.g. PT5M). */
        private Duration window;

        public int      getLimit()  { return limit; }
        public void     setLimit(int limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void     setWindow(Duration window) { this.window = window; }
    }
}
