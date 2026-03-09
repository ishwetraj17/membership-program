package com.firstclub.payments.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Payment gateway that delivered this event.
     * Scopes dedup keys to a specific provider — required for multi-provider support.
     * Defaults to "gateway" for backward compatibility with V37-and-earlier rows.
     */
    @Column(name = "provider", nullable = false, length = 32)
    @Builder.Default
    private String provider = "gateway";

    /** Unique identifier from the gateway — used to deduplicate redeliveries. */
    @Column(name = "event_id", unique = true, nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(nullable = false)
    @Builder.Default
    private boolean processed = false;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Earliest time this event is eligible for the next retry attempt. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
