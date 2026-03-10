package com.firstclub.ledger.revenue.service.impl;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.guard.GuardDecision;
import com.firstclub.ledger.revenue.guard.RevenueRecognitionGuard;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueCatchUpService;
import com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link RevenueCatchUpService}.
 *
 * <h3>Transaction design</h3>
 * This service is intentionally <em>non-transactional</em> at the method level.
 * Each schedule row is processed in its own pair of committed transactions:
 * <ol>
 *   <li>{@link RevenueCatchUpTransactionHelper} stamps the guard decision in
 *       a {@code REQUIRES_NEW} transaction and commits it.</li>
 *   <li>{@link RevenueRecognitionPostingService#postSingleRecognitionInRun} posts
 *       the ledger entry in a separate {@code REQUIRES_NEW} transaction.  Because
 *       the guard fields were already committed in step 1, the posting service
 *       sees the correct state on reload.</li>
 * </ol>
 * A failure on any individual row is logged and captured in the response DTO;
 * it does not roll back rows already successfully processed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevenueCatchUpServiceImpl implements RevenueCatchUpService {

    private final RevenueRecognitionScheduleRepository scheduleRepository;
    private final RevenueRecognitionPostingService    postingService;
    private final RevenueRecognitionGuard             guard;
    private final SubscriptionV2Repository            subscriptionRepository;
    private final InvoiceRepository                   invoiceRepository;
    private final RevenueCatchUpTransactionHelper     txHelper;

    /**
     * {@inheritDoc}
     *
     * <p>Processing order per row:
     * <ol>
     *   <li>Fetch current invoice and subscription status.</li>
     *   <li>Evaluate {@link RevenueRecognitionGuard}.</li>
     *   <li a. If {@code BLOCK} or {@code HALT}: commit SKIPPED stamp via helper.</li>
     *   <li b. If {@code DEFER}: commit guard fields, leave PENDING via helper.</li>
     *   <li c. If {@code ALLOW} or {@code FLAG}: commit guard fields + catchUpRun flag
     *          via helper, then delegate to the posting service.</li>
     * </ol>
     */
    @Override
    public RevenueRecognitionRunResponseDTO runCatchUp(LocalDate asOf) {
        long runId = System.currentTimeMillis();

        List<RevenueRecognitionSchedule> pending = scheduleRepository
                .findByRecognitionDateLessThanEqualAndStatus(asOf, RevenueRecognitionStatus.PENDING);

        int posted  = 0;
        int skipped = 0;
        int failed  = 0;
        List<Long> failedIds = new ArrayList<>();

        for (RevenueRecognitionSchedule schedule : pending) {
            Long scheduleId = schedule.getId();
            try {
                // 1. Resolve current state for guard evaluation
                InvoiceStatus invoiceStatus = resolveInvoiceStatus(schedule.getInvoiceId());
                SubscriptionStatusV2 subStatus = resolveSubscriptionStatus(schedule.getSubscriptionId());

                // 2. Guard decision
                RevenueRecognitionGuard.GuardResult result =
                        guard.evaluate(subStatus, invoiceStatus);
                GuardDecision decision = result.decision();

                // 3. Act on decision
                if (decision == GuardDecision.BLOCK || decision == GuardDecision.HALT) {
                    txHelper.stampAndSkip(scheduleId, decision,
                            result.policyCode(), result.reason());
                    skipped++;
                    log.info("Catch-up: schedule {} SKIPPED ({}) invoiceId={} reason={}",
                            scheduleId, decision, schedule.getInvoiceId(), result.reason());

                } else if (decision == GuardDecision.DEFER) {
                    txHelper.stampDefer(scheduleId, decision,
                            result.policyCode(), result.reason());
                    // leave PENDING — bump skipped counter so caller sees it wasn't posted
                    skipped++;
                    log.info("Catch-up: schedule {} DEFERRED ({}) invoiceId={}",
                            scheduleId, decision, schedule.getInvoiceId());

                } else {
                    // ALLOW or FLAG — stamp guard fields, set catchUpRun=true, then post
                    txHelper.stampAllowAndMarkCatchUp(scheduleId, decision,
                            result.policyCode(), result.reason());
                    postingService.postSingleRecognitionInRun(scheduleId, runId);
                    posted++;
                    log.debug("Catch-up: schedule {} POSTED ({})", scheduleId, decision);
                }

            } catch (Exception e) {
                log.error("Catch-up posting failed for schedule {} (invoiceId={}): {}",
                        scheduleId, schedule.getInvoiceId(), e.getMessage());
                failed++;
                failedIds.add(scheduleId);
            }
        }

        log.info("Catch-up run {} asOf={}: total={} posted={} skipped={} failed={}",
                runId, asOf, pending.size(), posted, skipped, failed);

        return RevenueRecognitionRunResponseDTO.builder()
                .date(asOf.toString())
                .scheduled(pending.size())
                .posted(posted)
                .failed(failed)
                .failedScheduleIds(failedIds)
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the invoice's current status, defaulting to {@link InvoiceStatus#OPEN}
     * if the invoice cannot be found (defensive — should not occur in practice).
     */
    private InvoiceStatus resolveInvoiceStatus(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        return invoice != null ? invoice.getStatus() : InvoiceStatus.OPEN;
    }

    /**
     * Returns the subscription's current status, defaulting to
     * {@link SubscriptionStatusV2#ACTIVE} if the subscription cannot be found.
     */
    private SubscriptionStatusV2 resolveSubscriptionStatus(Long subscriptionId) {
        SubscriptionV2 sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        return sub != null ? sub.getStatus() : SubscriptionStatusV2.ACTIVE;
    }
}
