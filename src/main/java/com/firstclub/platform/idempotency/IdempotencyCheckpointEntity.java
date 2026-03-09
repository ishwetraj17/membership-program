package com.firstclub.platform.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Records individual operation steps for an idempotent request.
 *
 * <p>Enables the checkpoint pattern: if a multi-step operation (e.g., create
 * subscription → charge payment → send email) is interrupted mid-way, the service
 * can query its checkpoints at retry time to resume from the last successful step
 * instead of re-running the entire operation.
 *
 * <h3>Example</h3>
 * <pre>
 *   checkpointService.record(merchantId, idempotencyKey,
 *       "CREATE_SUBSCRIPTION", "SUBSCRIPTION_SAVED", "SUCCESS",
 *       "Subscription", subscription.getId(), null);
 * </pre>
 */
@Entity
@Table(
    name = "idempotency_checkpoints",
    indexes = {
        @Index(name = "idx_idem_chk_merchant_key", columnList = "merchant_id, idempotency_key"),
        @Index(name = "idx_idem_chk_operation",    columnList = "merchant_id, idempotency_key, operation_type")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyCheckpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Merchant identifier of the idempotent request. */
    @Column(name = "merchant_id", length = 255, nullable = false)
    private String merchantId;

    /** Raw client-supplied Idempotency-Key value. */
    @Column(name = "idempotency_key", length = 255, nullable = false)
    private String idempotencyKey;

    /** High-level operation type (e.g., {@code "CREATE_SUBSCRIPTION"}). */
    @Column(name = "operation_type", length = 128, nullable = false)
    private String operationType;

    /** Name of the specific step within the operation (e.g., {@code "PAYMENT_CHARGED"}). */
    @Column(name = "step_name", length = 255, nullable = false)
    private String stepName;

    /** Step outcome: {@code "SUCCESS"}, {@code "SKIPPED"}, or {@code "FAILED"}. */
    @Column(name = "step_status", length = 32, nullable = false)
    private String stepStatus;

    /** Optional resource type created or modified by this step (e.g., {@code "Subscription"}). */
    @Column(name = "resource_type", length = 128)
    private String resourceType;

    /** Optional ID of the resource created or modified by this step. */
    @Column(name = "resource_id")
    private Long resourceId;

    /** Optional serialised JSON payload for detailed audit or replay context. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
