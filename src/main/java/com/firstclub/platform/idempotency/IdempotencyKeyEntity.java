package com.firstclub.platform.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Persists idempotency key records.
 *
 * <p>A record is created in {@link IdempotencyStatus#PROCESSING} state the first
 * time a key is seen, then transitioned to {@link IdempotencyStatus#COMPLETED} (or
 * a FAILED variant) once the operation finishes.  Subsequent requests with the same
 * key receive the stored response verbatim.
 *
 * <h3>Phase 3 — merchant scoping</h3>
 * <p>The {@code key} column stores a composite string of the form
 * {@code "{merchantId}:{rawIdempotencyKey}"} so that different tenants using the
 * same raw key value remain fully isolated within the same table.
 *
 * <h3>Phase 4 — status lifecycle</h3>
 * <p>Adds {@code status}, {@code idempotency_key} (raw key), lifecycle timestamps,
 * and request-tracing fields.
 */
@Entity
@Table(
    name = "idempotency_keys",
    indexes = {
        @Index(name = "idx_idempotency_expires_at", columnList = "expires_at"),
        @Index(name = "idx_idem_merchant_id",       columnList = "merchant_id"),
        @Index(name = "idx_idem_merchant_key",      columnList = "merchant_id, idempotency_key"),
        @Index(name = "idx_idem_status_started",    columnList = "status, processing_started_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "key", length = 255)
    private String key;                   // composite PK: "{merchantId}:{rawKey}"

    /** Raw idempotency key as supplied by the client (without the merchant prefix). */
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    /** SHA-256 of HTTP method + path + raw request body. */
    @Column(name = "request_hash", length = 128, nullable = false)
    private String requestHash;

    /** Serialised JSON response body — null until the operation completes. */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    /** HTTP status code returned by the original request. */
    @Column(name = "status_code")
    private Integer statusCode;

    /** Lifecycle status of this record. Null on legacy records (pre-V51). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private IdempotencyStatus status;

    /** Principal name of the request owner (nullable for anonymous). */
    @Column(name = "owner", length = 255)
    private String owner;

    /**
     * Authenticated merchant identifier stored alongside the composite PK for
     * the admin debug endpoint.  Mirrors the prefix of {@link #key}.
     */
    @Column(name = "merchant_id", length = 255)
    private String merchantId;

    /**
     * "{HTTP_METHOD}:{url-template}" of the endpoint that first used this key,
     * e.g. {@code "POST:/api/v2/subscriptions"}.  Used to detect cross-endpoint
     * key reuse on replayed requests.
     */
    @Column(name = "endpoint_signature", length = 255)
    private String endpointSignature;

    /**
     * Content-Type of the stored response, preserved for faithful replay
     * (usually {@code "application/json"}).
     */
    @Column(name = "content_type", length = 128)
    private String contentType;

    /** Set when the PROCESSING placeholder is first created. */
    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    /** Set when the record transitions to COMPLETED or a FAILED state. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Tracing: request ID of the originating HTTP request. */
    @Column(name = "request_id", length = 255)
    private String requestId;

    /** Tracing: correlation ID of the originating HTTP request. */
    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // ── Convenience predicates ────────────────────────────────────────────────

    /**
     * True when the response has been stored and is safe to replay.
     *
     * <p>For Phase-4 records this checks {@code status == COMPLETED}.
     * For legacy records (status is null) it falls back to checking whether
     * {@code responseBody} and {@code statusCode} are non-null.
     */
    public boolean isCompleted() {
        if (status == null) {
            // Legacy inference for pre-V51 records loaded from the DB.
            return responseBody != null && statusCode != null;
        }
        return status == IdempotencyStatus.COMPLETED;
    }

    /**
     * True when this record is in the PROCESSING state (request in-flight).
     *
     * <p>For legacy records (status is null) it infers from the absence of a
     * stored response.
     */
    public boolean isProcessing() {
        if (status == null) {
            return responseBody == null;
        }
        return status == IdempotencyStatus.PROCESSING;
    }

    /**
     * Legacy predicate kept for backward compatibility.
     *
     * @deprecated Use {@link #isCompleted()} instead.
     */
    @Deprecated(since = "Phase 4", forRemoval = true)
    public boolean isProcessed() {
        return isCompleted();
    }
}
