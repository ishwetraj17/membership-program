package com.firstclub.recon.mismatch;

import com.firstclub.recon.classification.ReconExpectation;
import com.firstclub.recon.classification.ReconSeverity;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.SettlementBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Detects <em>duplicate settlement batches</em>: cases where more than one
 * {@link com.firstclub.recon.entity.SettlementBatch} exists for the same
 * merchant and business date.
 *
 * <h3>Why this matters</h3>
 * A duplicate settlement batch means the same set of transactions may be settled
 * (and paid out) twice.  This is a critical financial defect that requires
 * immediate investigation and a manual reversal with the bank.
 *
 * <h3>Detection strategy</h3>
 * The repository query groups settlement batches by {@code (merchant_id, batch_date)}
 * and returns any pair where the count exceeds one.  Each such pair generates
 * a single {@link MismatchType#DUPLICATE_SETTLEMENT} mismatch with
 * {@link ReconSeverity#CRITICAL}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicateSettlementChecker {

    private final SettlementBatchRepository batchRepository;
    private final ReconMismatchRepository   mismatchRepository;

    /**
     * Checks for duplicate settlement batches on {@code date} across all merchants.
     *
     * @param date     the business date to inspect
     * @param reportId the owning {@link com.firstclub.recon.entity.ReconReport} ID
     * @return list of newly persisted mismatch records (one per duplicate pair)
     */
    @Transactional
    public List<ReconMismatch> check(LocalDate date, Long reportId) {
        List<ReconMismatch> created = batchRepository
                .findDuplicateMerchantBatchesForDate(date)
                .stream()
                .map(projection -> {
                    Long merchantId = projection.getMerchantId();
                    long count      = projection.getBatchCount();

                    String details = String.format(
                            "Merchant %d has %d settlement batches for date %s — expected exactly 1",
                            merchantId, count, date);

                    log.error("DUPLICATE SETTLEMENT DETECTED: merchantId={} date={} batchCount={}",
                            merchantId, date, count);

                    return mismatchRepository.save(ReconMismatch.builder()
                            .reportId(reportId)
                            .type(MismatchType.DUPLICATE_SETTLEMENT)
                            .merchantId(merchantId)
                            .expectation(ReconExpectation.UNEXPECTED_SYSTEM_ERROR)
                            .severity(ReconSeverity.CRITICAL)
                            .details(details)
                            .build());
                })
                .toList();

        log.info("Duplicate settlement check: date={} reportId={} duplicatesFound={}",
                date, reportId, created.size());
        return created;
    }
}
