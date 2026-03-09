package com.firstclub.platform.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, String> {

    /**
     * Looks up an idempotency record using the merchant-scoped composite PK
     * "{merchantId}:{rawKey}" stored in the {@code key} column.
     *
     * <p>Delegates to {@link #findById(Object)} using the composite string.
     * Provided as a named method for readability at call sites.
     */
    default Optional<IdempotencyKeyEntity> findByMerchantAndKey(
            String merchantId, String rawKey) {
        return findById(merchantId + ":" + rawKey);
    }

    /**
     * Phase-4 canonical lookup using the separate {@code merchant_id} and
     * {@code idempotency_key} columns populated for all records created after V51.
     */
    Optional<IdempotencyKeyEntity> findByMerchantIdAndIdempotencyKey(
            String merchantId, String idempotencyKey);

    /**
     * Returns PROCESSING records whose {@code processing_started_at} is before
     * the given threshold — used by the cleanup scheduler to detect stuck
     * in-flight records.
     */
    @Query("SELECT e FROM IdempotencyKeyEntity e " +
           "WHERE e.status = :status AND e.processingStartedAt < :threshold")
    List<IdempotencyKeyEntity> findStuckProcessing(
            @Param("status") IdempotencyStatus status,
            @Param("threshold") LocalDateTime threshold);

    /**
     * Bulk-updates stuck PROCESSING records to {@code newStatus}.
     *
     * @return the number of records updated
     */
    @Modifying
    @Query("UPDATE IdempotencyKeyEntity e " +
           "SET e.status = :newStatus, e.updatedAt = :now " +
           "WHERE e.status = :currentStatus AND e.processingStartedAt < :threshold")
    int resetStuckProcessing(
            @Param("currentStatus") IdempotencyStatus currentStatus,
            @Param("threshold")     LocalDateTime threshold,
            @Param("newStatus")     IdempotencyStatus newStatus,
            @Param("now")           LocalDateTime now);

    /**
     * Bulk-deletes all records whose {@code expires_at} is before the given
     * timestamp.  Called by the nightly cleanup job.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKeyEntity e WHERE e.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
