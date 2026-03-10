package com.firstclub.ledger.revenue.repository;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface RevenueRecognitionScheduleRepository
        extends JpaRepository<RevenueRecognitionSchedule, Long> {

    /** All schedules due on or before {@code date} that haven't been posted yet. */
    List<RevenueRecognitionSchedule> findByRecognitionDateLessThanEqualAndStatus(
            LocalDate date, RevenueRecognitionStatus status);

    /** All schedules for a specific invoice (used for idempotency check and reporting). */
    List<RevenueRecognitionSchedule> findByInvoiceId(Long invoiceId);

    /** All schedules for a subscription. */
    List<RevenueRecognitionSchedule> findBySubscriptionId(Long subscriptionId);

    /** Quick idempotency check — true if any schedules already exist for this invoice. */
    boolean existsByInvoiceId(Long invoiceId);

    /** All schedules with a recognition_date in [from, to] (inclusive). */
    List<RevenueRecognitionSchedule> findByRecognitionDateBetween(LocalDate from, LocalDate to);

    /** Sum of amounts for a given status within the date range. Returns null when no rows match. */
    @Query("SELECT SUM(r.amount) FROM RevenueRecognitionSchedule r " +
           "WHERE r.recognitionDate BETWEEN :from AND :to AND r.status = :status")
    BigDecimal sumAmountByDateRangeAndStatus(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("status") RevenueRecognitionStatus status);

    /** Platform-wide count by status — used by deep health checks. */
    long countByStatus(RevenueRecognitionStatus status);

    /**
     * Acquire a PESSIMISTIC_WRITE lock on a single schedule row.
     *
     * <p><b>Guard:</b> BusinessLockScope.REVENUE_RECOGNITION_SINGLE_POST
     * <p>Used in {@code RevenueRecognitionPostingServiceImpl.postSingleRecognition}
     * before the {@code status == POSTED} idempotency check to close the TOCTOU
     * window that would otherwise allow two concurrent callers to both post ledger
     * entries for the same schedule row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RevenueRecognitionSchedule r WHERE r.id = :id")
    java.util.Optional<RevenueRecognitionSchedule> findByIdWithLock(@Param("id") Long id);

    // ── Phase 14: fingerprint + ceiling helpers ────────────────────────────

    /** True if any schedule rows for this invoice already carry a fingerprint
     *  matching {@code fingerprint}.  Used for strong idempotency assertion. */
    boolean existsByGenerationFingerprint(String generationFingerprint);

    /** All schedule rows for an invoice that are still PENDING (not yet posted).
     *  Used by the force-regeneration path to delete stale pending rows. */
    List<RevenueRecognitionSchedule> findByInvoiceIdAndStatus(
            Long invoiceId, RevenueRecognitionStatus status);

    /** Sum of amounts for a given invoice and status.
     *  Returns {@code null} when no rows match — callers should coerce to ZERO. */
    @Query("SELECT SUM(r.amount) FROM RevenueRecognitionSchedule r " +
           "WHERE r.invoiceId = :invoiceId AND r.status = :status")
    BigDecimal sumAmountByInvoiceIdAndStatus(
            @Param("invoiceId") Long invoiceId,
            @Param("status") RevenueRecognitionStatus status);

    /** Sum of ALL amounts for a given invoice regardless of status.
     *  Represents the total scheduled amount that should equal the invoice total. */
    @Query("SELECT SUM(r.amount) FROM RevenueRecognitionSchedule r " +
           "WHERE r.invoiceId = :invoiceId")
    BigDecimal sumTotalAmountByInvoiceId(@Param("invoiceId") Long invoiceId);

    /** Sum of POSTED amounts for schedules on a specific date and merchant.
     *  Used by the waterfall projection updater. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RevenueRecognitionSchedule r " +
           "WHERE r.merchantId = :merchantId AND r.recognitionDate = :date " +
           "AND r.status = 'POSTED'")
    BigDecimal sumPostedAmountByMerchantAndDate(
            @Param("merchantId") Long merchantId,
            @Param("date") java.time.LocalDate date);

    // ── Phase 15: guard and drift-check helpers ────────────────────────────────

    /**
     * Count of PENDING rows for an invoice whose recognitionDate is strictly before
     * {@code today} — i.e. overdue rows that haven't been posted or skipped yet.
     * Used by {@link com.firstclub.ledger.revenue.audit.RevenueRecognitionDriftChecker}.
     */
    @Query("SELECT COUNT(r) FROM RevenueRecognitionSchedule r " +
           "WHERE r.invoiceId = :invoiceId " +
           "AND r.status = com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus.PENDING " +
           "AND r.recognitionDate < :today")
    long countOverduePendingByInvoiceId(
            @Param("invoiceId") Long invoiceId,
            @Param("today") LocalDate today);

    /** All schedule rows for an invoice ordered by recognition date ascending.
     *  Useful for ordered audit views and allocation verification. */
    List<RevenueRecognitionSchedule> findByInvoiceIdOrderByRecognitionDateAsc(Long invoiceId);
}
