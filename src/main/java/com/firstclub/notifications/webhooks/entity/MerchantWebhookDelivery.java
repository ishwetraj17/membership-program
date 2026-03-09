package com.firstclub.notifications.webhooks.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Audit record for every outbound webhook delivery attempt.
 *
 * <p>One row is created per (endpoint, event) pair when an event is enqueued.
 * The row is updated after each dispatch attempt until the delivery reaches
 * a terminal state ({@link MerchantWebhookDeliveryStatus#DELIVERED} or
 * {@link MerchantWebhookDeliveryStatus#GAVE_UP}).
 *
 * <p>The {@code signature} field stores the pre-computed HMAC-SHA256
 * in the format {@code sha256=<hex>} and is included in the outbound
 * {@code X-Webhook-Signature} header on every attempt.
 */
@Entity
@Table(
    name = "merchant_webhook_deliveries",
    indexes = {
        @Index(name = "idx_mwd_status_next_attempt", columnList = "status, next_attempt_at"),
        @Index(name = "idx_mwd_endpoint_id",         columnList = "endpoint_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class MerchantWebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** Full JSON payload that was / will be POST-ed to the endpoint. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Pre-computed HMAC-SHA256 signature: {@code sha256=<64 hex chars>}. */
    @Column(name = "signature", nullable = false, length = 255)
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MerchantWebhookDeliveryStatus status;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_response_code")
    private Integer lastResponseCode;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** When the next dispatch attempt may be made (null for terminal states). */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /**
     * Identity of the pod/process currently dispatching this delivery.
     * Pattern: {@code hostname:pid}. Cleared on completion.
     * Useful for detecting and monitoring stale in-flight deliveries.
     */
    @Column(name = "processing_owner", length = 128)
    private String processingOwner;

    /**
     * Timestamp set when dispatch begins; cleared when delivery reaches a
     * terminal or retriable state. Together with {@link #processingOwner}
     * enables diagnosing stuck deliveries.
     */
    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    /**
     * SHA-256 fingerprint of {@code endpointId|eventType|payload}.
     * Used to prevent re-enqueueing a delivery that was already DELIVERED
     * for the same (endpoint, event, payload) combination.
     */
    @Column(name = "delivery_fingerprint", length = 255)
    private String deliveryFingerprint;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
