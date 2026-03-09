package com.firstclub.notifications.webhooks.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A merchant-registered URL that will receive signed HTTP POST calls
 * for lifecycle events the merchant has subscribed to.
 *
 * <p>{@code subscribedEventsJson} is a JSON array of event-type strings,
 * e.g. {@code ["invoice.paid","payment.failed"]}. The wildcard {@code "*"} means
 * all events. Stored as TEXT for flexibility; validated on write by the service.
 *
 * <p>The {@code secret} is merchant-specific and never returned by the API.
 * It is used to produce an HMAC-SHA256 signature ({@code X-Webhook-Signature})
 * on every outbound delivery.
 */
@Entity
@Table(
    name = "merchant_webhook_endpoints",
    indexes = @Index(name = "idx_mwe_merchant_active", columnList = "merchant_id, active")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class MerchantWebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    /** HMAC signing secret; never exposed via the API response. */
    @Column(name = "secret", nullable = false, length = 255)
    private String secret;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** JSON array of subscribed event types, e.g. ["invoice.paid","payment.failed"]. */
    @Column(name = "subscribed_events_json", nullable = false, columnDefinition = "TEXT")
    private String subscribedEventsJson;

    /**
     * Running count of consecutive failed dispatch attempts across all deliveries.
     * Reset to 0 whenever any delivery to this endpoint succeeds (2xx).
     * Reaches {@code MerchantWebhookDeliveryServiceImpl.CONSECUTIVE_FAILURE_THRESHOLD}
     * to trigger auto-disable.
     */
    @Builder.Default
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    /**
     * Timestamp when the system automatically disabled this endpoint after
     * repeated failures. {@code null} means the endpoint has never been auto-disabled.
     * Re-enable via re-enable API (resets this field to null and reactivates).
     */
    @Column(name = "auto_disabled_at")
    private LocalDateTime autoDisabledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
