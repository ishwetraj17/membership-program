package com.firstclub.ledger.revenue.entity;

import com.firstclub.ledger.revenue.guard.GuardDecision;
import com.firstclub.ledger.revenue.guard.RecognitionPolicyCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row in the daily revenue recognition schedule for a paid recurring invoice.
 *
 * <p>When an invoice is paid, a schedule is generated that spreads the recognizable
 * amount ({@code grandTotal}) evenly across each day of the service period.  The
 * final day absorbs any rounding residue so that the schedule always sums to
 * exactly the invoice amount.
 *
 * <p>Each PENDING row is processed nightly by {@link com.firstclub.ledger.revenue.scheduler.RevenueRecognitionScheduler}.
 * On posting, a double-entry ledger record is written:
 * <ul>
 *   <li>DR SUBSCRIPTION_LIABILITY</li>
 *   <li>CR REVENUE_SUBSCRIPTIONS</li>
 * </ul>
 */
@Entity
@Table(name = "revenue_recognition_schedules")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RevenueRecognitionSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant scope — merchant that owns the subscription and invoice. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** The recurring subscription this recognition belongs to. */
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    /** The paid invoice whose amount is being spread over the service period. */
    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    /**
     * The day on which this slice of revenue should be recognized.
     * Daily schedules: one row per calendar day in [periodStart, periodEnd).
     */
    @Column(name = "recognition_date", nullable = false)
    private LocalDate recognitionDate;

    /** Revenue amount to recognize on this date. Precision matches invoice. */
    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private RevenueRecognitionStatus status = RevenueRecognitionStatus.PENDING;

    /**
     * Set once this schedule is POSTED.  Links back to the exact journal entry
     * that recognized this revenue, making the record fully auditable.
     */
    @Column(name = "ledger_entry_id")
    private Long ledgerEntryId;

    /**
     * SHA-256 fingerprint of the inputs used to generate this schedule set:
     * {@code invoiceId:subscriptionId:grandTotal:periodStart:periodEnd}.
     * All rows from one generation of an invoice share the same fingerprint.
     * Used for idempotency verification and audit ("what was the state of the
     * invoice when the schedule was generated?").
     */
    @Column(name = "generation_fingerprint", length = 255)
    private String generationFingerprint;

    /**
     * Identifies the {@code postDueRecognitionsForDate()} batch run that posted
     * this row.  Set to {@code System.currentTimeMillis()} at the start of each
     * batch invocation.  Allows operators to query "all rows posted in run X"
     * for incident response.  {@code null} if the row has not yet been posted
     * or was posted before Phase 14.
     */
    @Column(name = "posting_run_id")
    private Long postingRunId;

    /**
     * {@code true} when this row was generated via an explicit repair /
     * force-regeneration call ({@code POST /api/v2/admin/repair/revenue-recognition/{id}/regenerate?force=true})
     * rather than the normal invoice-payment trigger.  Used in reports to
     * separate normal recognition runs from catch-up runs.
     */
    @Column(name = "catch_up_run", nullable = false)
    @Builder.Default
    private boolean catchUpRun = false;

    /**
     * Optimistic-lock version.  Added in V41 to provide OCC backstop for the
     * {@code postSingleRecognition} path (Guard: BusinessLockScope.REVENUE_RECOGNITION_SINGLE_POST).
     * The primary guard is a {@code SELECT FOR UPDATE} before the status check;
     * this version column catches any edge case where a second committer
     * bypasses the pessimistic lock (e.g., non-transactional test scenario).
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    // ── Phase 15: Guard and minor-unit fields ─────────────────────────────────

    /**
     * The expected recognition amount in minor currency units (e.g. paise).
     * {@code amount × 100}, rounded to nearest integer.
     * Set at schedule generation time by {@link com.firstclub.ledger.revenue.RevenueScheduleAllocator}.
     */
    @Column(name = "expected_amount_minor")
    private Long expectedAmountMinor;

    /**
     * The actual recognized amount in minor currency units.
     * Set to {@code expected_amount_minor} (or derived from {@code amount}) when
     * the row is successfully {@code POSTED}.
     */
    @Column(name = "recognized_amount_minor")
    private Long recognizedAmountMinor;

    /**
     * The rounding remainder absorbed by the last schedule row, in minor units.
     * Zero for all rows except the final slice in a multi-day schedule.
     * Enables integer-arithmetic verification: {@code sum(expectedAmountMinor) == invoiceMinorTotal}.
     */
    @Column(name = "rounding_adjustment_minor")
    private Long roundingAdjustmentMinor;

    /**
     * The {@link RecognitionPolicyCode} applied by the guard at last evaluation.
     * {@code null} until the guard has evaluated this row (set by
     * {@link com.firstclub.ledger.revenue.service.impl.RevenueCatchUpServiceImpl}
     * or the normal posting path).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "policy_code", length = 40)
    private RecognitionPolicyCode policyCode;

    /**
     * The {@link GuardDecision} applied by
     * {@link com.firstclub.ledger.revenue.guard.RevenueRecognitionGuard} at last evaluation.
     * {@code null} until the guard has evaluated this row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "guard_decision", length = 20)
    private GuardDecision guardDecision;

    /**
     * Human-readable explanation for the guard decision.
     * Stored for audit and operator dashboards.
     */
    @Column(name = "guard_reason", columnDefinition = "TEXT")
    private String guardReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
