package com.firstclub.platform.ratelimit;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit record written whenever a rate limit is exceeded.
 *
 * <p>This table exists purely for ops dashboards and incident investigation.
 * Writes are best-effort (see {@link RedisSlidingWindowRateLimiter#persistBlockEvent}).
 * A failure to write must never affect the rate-limit decision itself.
 */
@Entity
@Table(
    name = "rate_limit_events",
    indexes = {
        @Index(name = "idx_rl_events_blocked_at", columnList = "blocked, created_at"),
        @Index(name = "idx_rl_events_category",  columnList = "category")
    }
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Rate limit policy name (e.g. {@code AUTH_BY_IP}). */
    @Column(name = "category", length = 64, nullable = false)
    private String category;

    /** Fully-qualified Redis key used for this limit check. */
    @Column(name = "subject_key", length = 255, nullable = false)
    private String subjectKey;

    /** Merchant ID when the policy is merchant-scoped, otherwise {@code null}. */
    @Column(name = "merchant_id", length = 255)
    private String merchantId;

    /** {@code true} when the request was blocked; informational rows are not stored. */
    @Column(name = "blocked", nullable = false)
    private boolean blocked;

    /** Idempotency-Key or correlation-ID from the blocked request (may be null). */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** Human-readable explanation of why the request was blocked. */
    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
