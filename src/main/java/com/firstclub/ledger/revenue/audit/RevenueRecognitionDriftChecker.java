package com.firstclub.ledger.revenue.audit;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Detects discrepancies between the total <em>scheduled</em> revenue recognition
 * amount and the total <em>recognized</em> (POSTED) amount for a given invoice.
 *
 * <h3>Types of drift detected</h3>
 * <ol>
 *   <li><strong>Amount drift</strong> — {@code recognized ≠ scheduled}.
 *       Can happen if a schedule row was deleted after posting, or if the
 *       schedule total no longer equals the invoice amount (generation bug).</li>
 *   <li><strong>Timing drift</strong> — one or more PENDING rows have a
 *       {@code recognition_date} in the past, meaning the nightly job did not
 *       post them on time.  These are candidates for a catch-up run.</li>
 * </ol>
 *
 * <h3>Integration with catch-up</h3>
 * Use {@link com.firstclub.ledger.revenue.service.RevenueCatchUpService#runCatchUp}
 * to resolve timing drift after this checker identifies affected invoices.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RevenueRecognitionDriftChecker {

    private final RevenueRecognitionScheduleRepository scheduleRepository;

    /**
     * Checks for recognition drift on a single invoice.
     *
     * @param invoiceId the invoice to inspect
     * @return a {@link DriftCheckResult}; {@link DriftCheckResult#hasDrift()} is
     *         {@code true} when action is required
     */
    @Transactional(readOnly = true)
    public DriftCheckResult checkDrift(Long invoiceId) {
        BigDecimal scheduled  = scheduleRepository.sumTotalAmountByInvoiceId(invoiceId);
        BigDecimal recognized = scheduleRepository
                .sumAmountByInvoiceIdAndStatus(invoiceId, RevenueRecognitionStatus.POSTED);
        long overdueCount = scheduleRepository
                .countOverduePendingByInvoiceId(invoiceId, LocalDate.now());

        DriftCheckResult result = DriftCheckResult.of(invoiceId, scheduled, recognized, overdueCount);

        if (result.hasDrift()) {
            log.warn(
                    "Revenue recognition drift detected for invoice {}: "
                    + "scheduled={} recognized={} delta={} overdueCount={}",
                    invoiceId, result.scheduledTotal(), result.recognizedTotal(),
                    result.delta(), result.pendingOverdueCount());
        }
        return result;
    }
}
