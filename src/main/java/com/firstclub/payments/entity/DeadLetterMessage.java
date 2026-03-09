package com.firstclub.payments.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "dead_letter_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class DeadLetterMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Origin of the failing message, e.g. "WEBHOOK". */
    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Coarse failure category copied from the outbox event at DLQ write time.
     * Matches {@code OutboxEvent.failureCategory} values.
     */
    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    /**
     * Merchant ID copied from the outbox event — enables per-merchant DLQ filtering
     * without parsing the pipe-delimited payload.
     */
    @Column(name = "merchant_id")
    private Long merchantId;
}
