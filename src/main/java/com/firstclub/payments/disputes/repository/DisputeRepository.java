package com.firstclub.payments.disputes.repository;

import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    /** Tenant-safe single-record lookup. */
    Optional<Dispute> findByMerchantIdAndId(Long merchantId, Long id);

    /** All disputes for a given payment, scoped to the merchant. */
    List<Dispute> findByMerchantIdAndPaymentId(Long merchantId, Long paymentId);

    /** Filter disputes for a merchant by status. */
    List<Dispute> findByMerchantIdAndStatus(Long merchantId, DisputeStatus status);

    /** All disputes for a merchant (unfiltered). */
    List<Dispute> findByMerchantId(Long merchantId);

    /**
     * True if any dispute for the given payment is in one of the supplied statuses.
     * Used to enforce the one-active-dispute-per-payment rule.
     */
    boolean existsByPaymentIdAndStatusIn(Long paymentId, List<DisputeStatus> statuses);

    /**
     * Loads a dispute row with a pessimistic write lock (SELECT FOR UPDATE).
     *
     * <p>Must be called inside an active transaction.  The lock is held until
     * the transaction commits or rolls back, serialising concurrent mutations
     * and ensuring callers read the latest committed state of the dispute
     * (prevents write-skew between the ACTIVE_STATUSES check and the
     * accounting post in {@code resolveDispute} / {@code moveToUnderReview}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Dispute d WHERE d.id = :id")
    Optional<Dispute> findByIdForUpdate(@Param("id") Long id);

    // ── Phase 15: due-soon query ──────────────────────────────────────────
    /**
     * Disputes whose evidence deadline is on or before {@code cutoff} and whose
     * status is still active (OPEN or UNDER_REVIEW).
     * Used by {@code DisputeDueDateCheckerService} and the admin due-soon endpoint.
     */
    List<Dispute> findByStatusInAndDueByBefore(List<DisputeStatus> statuses, LocalDateTime cutoff);
}

