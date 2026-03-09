package com.firstclub.platform.ratelimit.annotation;

import com.firstclub.platform.ratelimit.RateLimitPolicy;

import java.lang.annotation.*;

/**
 * Marks a controller method as rate-limited under one or more
 * {@link RateLimitPolicy policies}.
 *
 * <p>The {@link com.firstclub.platform.ratelimit.RateLimitInterceptor
 * RateLimitInterceptor} reads this annotation before the handler method
 * executes and enforces each listed policy in order.  If any policy is
 * exceeded, the request is rejected with HTTP 429 and the downstream
 * business logic is never invoked.
 *
 * <h3>Usage examples</h3>
 * <pre>
 * // Login: enforce both IP-level and email-level rate limits
 * {@literal @}RateLimit({RateLimitPolicy.AUTH_BY_IP})
 * public ResponseEntity<?> login(...) { ... }
 *
 * // Webhook ingest: rate limit per provider + IP
 * {@literal @}RateLimit(RateLimitPolicy.WEBHOOK_INGEST)
 * public ResponseEntity<?> receiveWebhook(...) { ... }
 *
 * // Payment confirm: limit per merchant + customer
 * {@literal @}RateLimit(RateLimitPolicy.PAYMENT_CONFIRM)
 * public ResponseEntity<?> confirm(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    /** One or more policies to enforce. Applied in array order. */
    RateLimitPolicy[] value();
}
