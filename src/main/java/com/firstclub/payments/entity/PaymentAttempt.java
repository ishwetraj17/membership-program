package com.firstclub.payments.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A single attempt to charge a customer via a payment gateway.
 *
 * <p>Attempts are <strong>immutable</strong> once they reach a terminal state
 * ({@code CAPTURED}, {@code FAILED}, or {@code TIMEOUT}).  A new attempt must
 * be created for each retry of a retriable failure.
 *
 * <p>The {@code attemptNumber} starts at 1 and increments per payment intent.
 */
@Entity
@Table(
    name = "payment_attempts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_attempts_intent_number",
                columnNames = {"payment_intent_id", "attempt_number"})
    },
    indexes = {
        @Index(name = "idx_payment_attempts_pi_id",
               columnList = "payment_intent_id"),
        @Index(name = "idx_payment_attempts_gateway_ref",
               columnList = "gateway_name, gateway_reference")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_intent_id", nullable = false)
    @ToString.Exclude
    private PaymentIntentV2 paymentIntent;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "gateway_name", nullable = false, length = 64)
    private String gatewayName;

    /** Gateway-assigned transaction reference issued after dispatch. */
    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    /** SHA-256 hash of the normalized request payload (for deduplication). */
    @Column(name = "request_hash", length = 128)
    private String requestHash;

    @Column(name = "response_code", length = 64)
    private String responseCode;

    @Column(name = "response_message", columnDefinition = "TEXT")
    private String responseMessage;

    /** Round-trip latency in milliseconds to the gateway. */
    @Column(name = "latency_ms")
    private Long latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private PaymentAttemptStatus status = PaymentAttemptStatus.STARTED;

    /** Set only when status is FAILED or TIMEOUT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", length = 32)
    private FailureCategory failureCategory;

    /** If true, the parent payment intent may spawn a new attempt on failure. */
    @Column(nullable = false)
    @Builder.Default
    private boolean retriable = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * JSON-serialised {@code RoutingDecisionSnapshot} captured at the time the gateway
     * was selected for this attempt.  Persisted for post-mortem audit and routing
     * observability dashboards.  {@code null} for pre-Phase-5 attempts and for attempts
     * whose routing fell back to a request hint (no matching routing rules).
     */
    @Column(name = "routing_snapshot_json", columnDefinition = "TEXT")
    private String routingSnapshotJson;

    // ── Phase 8: Gateway hardening fields ────────────────────────────────────

    /**
     * Deterministic idempotency key sent to the gateway.
     * Format: {@code firstclub:{intentId}:{attemptNumber}}.
     * Unique (partial index on non-NULL values) to prevent duplicate submissions.
     */
    @Column(name = "gateway_idempotency_key", length = 200, unique = true)
    private String gatewayIdempotencyKey;

    /**
     * Gateway-assigned transaction identifier returned in the synchronous response.
     * May be absent for {@code UNKNOWN} attempts where no response was received.
     */
    @Column(name = "gateway_transaction_id", length = 128)
    private String gatewayTransactionId;

    /**
     * SHA-256 hash of the normalised request payload.
     * Used for deduplication and audit; complements the legacy {@code request_hash} field.
     */
    @Column(name = "request_payload_hash", length = 128)
    private String requestPayloadHash;

    /** Raw JSON response received from the gateway; {@code null} for timed-out attempts. */
    @Column(name = "response_payload_json", columnDefinition = "TEXT")
    private String responsePayloadJson;

    /**
     * Identifier for the application node that dispatched this attempt.
     * Combines hostname and instance UUID for traceability across replicas.
     */
    @Column(name = "processor_node_id", length = 255)
    private String processorNodeId;

    /**
     * Timestamp when the gateway request was dispatched.
     * Distinct from {@code createdAt} (row write time) to measure actual latency.
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;
}
