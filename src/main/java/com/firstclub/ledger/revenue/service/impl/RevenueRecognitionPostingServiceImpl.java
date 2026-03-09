package com.firstclub.ledger.revenue.service.impl;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RevenueRecognitionPostingServiceImpl implements RevenueRecognitionPostingService {

    // Ledger account names (must match AccountSeeder / ledger_accounts table)
    static final String ACCOUNT_LIABILITY = "SUBSCRIPTION_LIABILITY";
    static final String ACCOUNT_REVENUE   = "REVENUE_SUBSCRIPTIONS";

    private final RevenueRecognitionScheduleRepository scheduleRepository;
    private final LedgerService ledgerService;

    /**
     * Self-injection for transactional self-invocation.  Direct calls to
     * {@link #postSingleRecognitionInRun} from within this class would bypass Spring's
     * proxy and lose the {@code REQUIRES_NEW} transaction boundary.
     */
    @Autowired
    @Lazy
    private RevenueRecognitionPostingService self;

    /**
     * {@inheritDoc}
     *
     * <p>Failure policy: if a ledger posting throws an exception, the schedule
     * remains {@code PENDING} (it is NOT moved to {@code FAILED}) so that it will
     * automatically be retried on the next run.  The failure is logged and captured
     * in the response DTO so operators can investigate without having to manually
     * reset state.
     *
     * <p>Phase 14: a run ID ({@code System.currentTimeMillis()}) is generated
     * once and stamped on every schedule row posted in this invocation.
     */
    @Override
    public RevenueRecognitionRunResponseDTO postDueRecognitionsForDate(LocalDate date) {
        // Monotonic run ID — unique per batch invocation, stored on each posted row
        long postingRunId = System.currentTimeMillis();

        List<RevenueRecognitionSchedule> pending = scheduleRepository
                .findByRecognitionDateLessThanEqualAndStatus(date, RevenueRecognitionStatus.PENDING);

        int posted = 0;
        int failed = 0;
        List<Long> failedIds = new ArrayList<>();

        for (RevenueRecognitionSchedule schedule : pending) {
            try {
                self.postSingleRecognitionInRun(schedule.getId(), postingRunId);
                posted++;
            } catch (Exception e) {
                log.error("Revenue recognition posting failed for schedule {} (invoice {}, date {}): {}",
                        schedule.getId(), schedule.getInvoiceId(),
                        schedule.getRecognitionDate(), e.getMessage());
                failed++;
                failedIds.add(schedule.getId());
                // Schedule remains PENDING — will be retried on next run
            }
        }

        log.info("Revenue recognition run {} for date {}: scheduled={}, posted={}, failed={}",
                postingRunId, date, pending.size(), posted, failed);

        return RevenueRecognitionRunResponseDTO.builder()
                .date(date.toString())
                .scheduled(pending.size())
                .posted(posted)
                .failed(failed)
                .failedScheduleIds(failedIds)
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #postSingleRecognitionInRun(Long, Long)} with a
     * {@code null} run ID.  Suitable for one-off administrative postings
     * where run-level tracking is not required.
     */
    @Override
    public void postSingleRecognition(Long scheduleId) {
        postSingleRecognitionInRun(scheduleId, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs in its own {@code REQUIRES_NEW} transaction so that a failure rolls
     * back only this schedule's ledger lines, not the entire batch.
     *
     * <p><b>Concurrency guard:</b> BusinessLockScope.REVENUE_RECOGNITION_SINGLE_POST
     * <br>A {@code SELECT FOR UPDATE} (pessimistic write lock) is acquired on the
     * schedule row <em>before</em> checking {@code status == POSTED}.  This closes
     * the TOCTOU window: two concurrent callers both reading {@code PENDING} before
     * either commits would both post duplicate ledger entries without this lock.
     * <br>With the lock held, the second caller blocks until the first commits;
     * it then re-reads the row (still inside its own REQUIRES_NEW transaction),
     * sees {@code status = POSTED}, and exits without posting a second entry.
     * <br>The {@code @Version} field on the entity is a backstop for any
     * non-pessimistic execution path (e.g. test mocks that skip the lock query).
     *
     * <p><b>Ceiling check (Phase 14):</b> Before posting, verifies that
     * {@code sum(POSTED for invoice) + this row's amount ≤ sum(ALL scheduled for invoice)}.
     * This prevents the total recognised amount from ever exceeding the scheduled
     * total even if a generation bug created extra rows.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postSingleRecognitionInRun(Long scheduleId, Long postingRunId) {
        // Acquire row-level write lock before reading status — eliminates TOCTOU race.
        // Guard: BusinessLockScope.REVENUE_RECOGNITION_SINGLE_POST
        RevenueRecognitionSchedule schedule = scheduleRepository.findByIdWithLock(scheduleId)
                .orElseThrow(() -> new MembershipException(
                        "Revenue recognition schedule not found: " + scheduleId,
                        "SCHEDULE_NOT_FOUND", HttpStatus.NOT_FOUND));

        // Idempotency guard — already posted, nothing to do.
        // Safe under concurrency because the lock above serializes all callers.
        if (schedule.getStatus() == RevenueRecognitionStatus.POSTED) {
            log.debug("Schedule {} already POSTED (lock held), skipping", scheduleId);
            return;
        }

        // Ceiling check — recognized amount must not exceed total scheduled amount
        enforceRecognitionCeiling(schedule);

        // Post double-entry: DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS
        LedgerEntry entry = ledgerService.postEntry(
                LedgerEntryType.REVENUE_RECOGNIZED,
                LedgerReferenceType.REVENUE_RECOGNITION_SCHEDULE,
                scheduleId,
                schedule.getCurrency(),
                List.of(
                        LedgerLineRequest.builder()
                                .accountName(ACCOUNT_LIABILITY)
                                .direction(LineDirection.DEBIT)
                                .amount(schedule.getAmount())
                                .build(),
                        LedgerLineRequest.builder()
                                .accountName(ACCOUNT_REVENUE)
                                .direction(LineDirection.CREDIT)
                                .amount(schedule.getAmount())
                                .build()
                )
        );

        schedule.setStatus(RevenueRecognitionStatus.POSTED);
        schedule.setLedgerEntryId(entry.getId());
        schedule.setPostingRunId(postingRunId);
        scheduleRepository.save(schedule);

        log.debug("Posted revenue recognition schedule {} → ledger entry {} (run {})",
                scheduleId, entry.getId(), postingRunId);
    }

    /**
     * Verifies that posting this schedule row would not cause the total recognised
     * amount for the invoice to exceed the total scheduled amount.
     *
     * <p>The scheduled total should equal the invoice amount (invariant maintained by
     * {@code ScheduleTotalEqualsInvoiceAmountChecker}).  Posting beyond that total
     * would overstate earned revenue.
     *
     * @throws MembershipException (HTTP 422) if the ceiling would be breached
     */
    private void enforceRecognitionCeiling(RevenueRecognitionSchedule schedule) {
        long invoiceId = schedule.getInvoiceId();

        BigDecimal alreadyPosted = scheduleRepository
                .sumAmountByInvoiceIdAndStatus(invoiceId, RevenueRecognitionStatus.POSTED);
        if (alreadyPosted == null) alreadyPosted = BigDecimal.ZERO;

        BigDecimal scheduledTotal = scheduleRepository.sumTotalAmountByInvoiceId(invoiceId);
        if (scheduledTotal == null) scheduledTotal = BigDecimal.ZERO;

        BigDecimal wouldRecognize = alreadyPosted.add(schedule.getAmount());
        if (wouldRecognize.compareTo(scheduledTotal) > 0) {
            throw new MembershipException(
                    String.format(
                            "Revenue recognition ceiling breached for invoice %d: " +
                            "already posted=%s, this row=%s, total scheduled=%s",
                            invoiceId, alreadyPosted, schedule.getAmount(), scheduledTotal),
                    "REVENUE_CEILING_BREACHED",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
