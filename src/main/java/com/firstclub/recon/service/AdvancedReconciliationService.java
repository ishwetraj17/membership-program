package com.firstclub.recon.service;

import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.ReconWindowPolicy;
import com.firstclub.recon.classification.ReconExpectationClassifier;
import com.firstclub.recon.classification.ReconExpectationClassifier.ClassificationResult;
import com.firstclub.recon.dto.ReconMismatchDTO;
import com.firstclub.recon.entity.*;
import com.firstclub.recon.gateway.OrphanGatewayPaymentChecker;
import com.firstclub.recon.mismatch.DuplicateSettlementChecker;
import com.firstclub.recon.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Advanced multi-layer reconciliation:
 * <ol>
 *   <li>Payment vs Ledger settlement total (Layer 2)</li>
 *   <li>Ledger settlement total vs Settlement batch gross (Layer 3)</li>
 *   <li>Settlement batch net vs imported external statement total (Layer 4)</li>
 * </ol>
 * Mismatches created here are linked to an existing {@link ReconReport} row.
 *
 * <p>Phase 14 additions: taxonomy-aware run with timing-window classification,
 * orphaned gateway payment detection, and duplicate settlement detection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvancedReconciliationService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final PaymentRepository             paymentRepository;
    private final SettlementRepository          settlementRepository;
    private final SettlementBatchRepository     batchRepository;
    private final ExternalStatementImportRepository importRepository;
    private final ReconMismatchRepository       mismatchRepository;
    private final ReconReportRepository         reportRepository;
    // Phase 14 beans
    private final ReconWindowPolicy             windowPolicy;
    private final ReconExpectationClassifier    classifier;
    private final OrphanGatewayPaymentChecker   orphanChecker;
    private final DuplicateSettlementChecker    dupSettlementChecker;

    // ---------------------------------------------------------------
    // Phase 14: Taxonomy-aware full reconciliation run
    // ---------------------------------------------------------------

    /**
     * Runs a full taxonomy-aware reconciliation pass for {@code date}.
     *
     * <p>Steps performed:
     * <ol>
     *   <li>Ensure a {@link ReconReport} exists for the date; create one if not.</li>
     *   <li>Run Layer-2 (payment vs ledger) reconciliation with timing-window classification.</li>
     *   <li>Detect orphaned gateway payments.</li>
     *   <li>Detect duplicate settlement batches.</li>
     * </ol>
     *
     * @return list of all mismatches produced (classified with expectation + severity)
     */
    @Transactional
    public List<ReconMismatchDTO> runForDateWithWindow(LocalDate date) {
        ReconReport report = reportRepository.findByReportDate(date)
                .orElseGet(() -> reportRepository.save(ReconReport.builder()
                        .reportDate(date)
                        .expectedTotal(BigDecimal.ZERO)
                        .actualTotal(BigDecimal.ZERO)
                        .mismatchCount(0)
                        .build()));

        ReconWindowPolicy.ReconWindow window = windowPolicy.windowFor(date);
        log.info("Running taxonomy-aware recon for date={} window=[{},{}]",
                date, window.extendedStart(), window.extendedEnd());

        List<ReconMismatch> all = new ArrayList<>();

        // Layer 2 with window-aware classification
        all.addAll(reconcilePaymentToLedgerWithWindow(date, report.getId(), window));
        // Orphan gateway payment check
        all.addAll(orphanChecker.check(report.getId()));
        // Duplicate settlement check
        all.addAll(dupSettlementChecker.check(date, report.getId()));

        log.info("Taxonomy-aware recon complete: date={} totalMismatches={}", date, all.size());
        return all.stream().map(ReconMismatchDTO::from).toList();
    }

    /**
     * Returns all mismatches whose {@code gateway_transaction_id} is non-null
     * and whose type is {@link MismatchType#ORPHAN_GATEWAY_PAYMENT}.
     * Used by the GET /orphaned-gateway-payments endpoint.
     */
    @Transactional(readOnly = true)
    public List<ReconMismatchDTO> listOrphanedGatewayPayments() {
        return mismatchRepository
                .findByTypeOrderByCreatedAtDesc(MismatchType.ORPHAN_GATEWAY_PAYMENT)
                .stream()
                .map(ReconMismatchDTO::from)
                .toList();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private List<ReconMismatch> reconcilePaymentToLedgerWithWindow(
            LocalDate date, Long reportId, ReconWindowPolicy.ReconWindow window) {

        LocalDateTime start = window.extendedStart();
        LocalDateTime end   = window.extendedEnd();

        BigDecimal paymentTotal = paymentRepository
                .findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, start, end)
                .stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal settlementTotal = settlementRepository
                .findBySettlementDate(date)
                .map(Settlement::getTotalAmount)
                .orElse(BigDecimal.ZERO);

        List<ReconMismatch> created = new ArrayList<>();
        if (paymentTotal.subtract(settlementTotal).abs().compareTo(TOLERANCE) > 0) {
            // Determine whether the discrepancy is within the timing margin
            boolean nearBoundary = !paymentTotal.equals(BigDecimal.ZERO)
                    && windowPolicy.isNearBoundary(date, start);
            ClassificationResult cr = classifier.classify(
                    MismatchType.PAYMENT_LEDGER_VARIANCE, nearBoundary);

            String details = String.format(
                    "Payment gross %s differs from ledger settlement %s (date=%s, delta=%s, window=%d min)",
                    paymentTotal, settlementTotal, date,
                    paymentTotal.subtract(settlementTotal), windowPolicy.getWindowMinutes());

            ReconMismatch m = mismatchRepository.save(ReconMismatch.builder()
                    .reportId(reportId)
                    .type(MismatchType.PAYMENT_LEDGER_VARIANCE)
                    .expectation(cr.expectation())
                    .severity(cr.severity())
                    .details(details)
                    .build());
            created.add(m);
        }
        return created;
    }

    // ---------------------------------------------------------------
    // Layer 2: captured payment total vs ledger settlement total
    // ---------------------------------------------------------------

    @Transactional
    public List<ReconMismatchDTO> reconcilePaymentToLedger(LocalDate date, Long reportId) {
        validateReport(reportId);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end   = date.plusDays(1).atStartOfDay();

        BigDecimal paymentTotal = paymentRepository
                .findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, start, end)
                .stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal settlementTotal = settlementRepository
                .findBySettlementDate(date)
                .map(Settlement::getTotalAmount)
                .orElse(BigDecimal.ZERO);

        List<ReconMismatch> created = new ArrayList<>();
        if (paymentTotal.subtract(settlementTotal).abs().compareTo(TOLERANCE) > 0) {
            String details = String.format(
                    "Payment gross %s differs from ledger settlement %s (date=%s, delta=%s)",
                    paymentTotal, settlementTotal, date,
                    paymentTotal.subtract(settlementTotal));
            created.add(mismatchRepository.save(ReconMismatch.builder()
                    .reportId(reportId)
                    .type(MismatchType.PAYMENT_LEDGER_VARIANCE)
                    .details(details)
                    .build()));
        }
        log.info("Layer-2 recon date={}: paymentTotal={} settlementTotal={} mismatches={}", date, paymentTotal, settlementTotal, created.size());
        return created.stream().map(ReconMismatchDTO::from).toList();
    }

    // ---------------------------------------------------------------
    // Layer 3: ledger settlement total vs settlement batch gross
    // ---------------------------------------------------------------

    @Transactional
    public List<ReconMismatchDTO> reconcileLedgerToSettlementBatch(Long batchId, Long reportId) {
        validateReport(reportId);
        SettlementBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new EntityNotFoundException("Batch not found: " + batchId));

        BigDecimal settlementTotal = settlementRepository
                .findBySettlementDate(batch.getBatchDate())
                .map(Settlement::getTotalAmount)
                .orElse(BigDecimal.ZERO);

        BigDecimal batchGross = batch.getGrossAmount();
        List<ReconMismatch> created = new ArrayList<>();

        if (batchGross.subtract(settlementTotal).abs().compareTo(TOLERANCE) > 0) {
            String details = String.format(
                    "Batch gross %s differs from ledger settlement total %s (batchId=%d, date=%s)",
                    batchGross, settlementTotal, batchId, batch.getBatchDate());
            created.add(mismatchRepository.save(ReconMismatch.builder()
                    .reportId(reportId)
                    .type(MismatchType.LEDGER_BATCH_VARIANCE)
                    .details(details)
                    .build()));
        }
        log.info("Layer-3 recon batchId={}: batchGross={} settlementTotal={} mismatches={}", batchId, batchGross, settlementTotal, created.size());
        return created.stream().map(ReconMismatchDTO::from).toList();
    }

    // ---------------------------------------------------------------
    // Layer 4: settlement batch gross vs external statement total
    // ---------------------------------------------------------------

    @Transactional
    public List<ReconMismatchDTO> reconcileSettlementBatchToStatement(Long batchId, Long importId, Long reportId) {
        validateReport(reportId);
        SettlementBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new EntityNotFoundException("Batch not found: " + batchId));
        ExternalStatementImport imp = importRepository.findById(importId)
                .orElseThrow(() -> new EntityNotFoundException("Statement import not found: " + importId));

        BigDecimal batchGross   = batch.getGrossAmount();
        BigDecimal statementAmt = imp.getTotalAmount();
        List<ReconMismatch> created = new ArrayList<>();

        if (batchGross.subtract(statementAmt).abs().compareTo(TOLERANCE) > 0) {
            String details = String.format(
                    "Batch gross %s differs from external statement total %s (batchId=%d, importId=%d, date=%s)",
                    batchGross, statementAmt, batchId, importId, batch.getBatchDate());
            created.add(mismatchRepository.save(ReconMismatch.builder()
                    .reportId(reportId)
                    .type(MismatchType.BATCH_STATEMENT_VARIANCE)
                    .details(details)
                    .build()));
        }
        log.info("Layer-4 recon batchId={} importId={}: batchGross={} statementAmt={} mismatches={}", batchId, importId, batchGross, statementAmt, created.size());
        return created.stream().map(ReconMismatchDTO::from).toList();
    }

    // ---------------------------------------------------------------
    // Mismatch lifecycle management
    // ---------------------------------------------------------------

    @Transactional
    public ReconMismatchDTO acknowledgeMismatch(Long mismatchId, Long ownerUserId) {
        ReconMismatch m = getMismatch(mismatchId);
        m.setStatus(ReconMismatchStatus.ACKNOWLEDGED);
        m.setOwnerUserId(ownerUserId);
        return ReconMismatchDTO.from(mismatchRepository.save(m));
    }

    @Transactional
    public ReconMismatchDTO resolveMismatch(Long mismatchId, String resolutionNote, Long ownerUserId) {
        ReconMismatch m = getMismatch(mismatchId);
        m.setStatus(ReconMismatchStatus.RESOLVED);
        m.setResolutionNote(resolutionNote);
        if (ownerUserId != null) m.setOwnerUserId(ownerUserId);
        return ReconMismatchDTO.from(mismatchRepository.save(m));
    }

    @Transactional
    public ReconMismatchDTO ignoreMismatch(Long mismatchId, String reason) {
        ReconMismatch m = getMismatch(mismatchId);
        m.setStatus(ReconMismatchStatus.IGNORED);
        m.setResolutionNote(reason);
        return ReconMismatchDTO.from(mismatchRepository.save(m));
    }

    @Transactional(readOnly = true)
    public Page<ReconMismatchDTO> listMismatches(ReconMismatchStatus status, Pageable pageable) {
        Page<ReconMismatch> page = (status != null)
                ? mismatchRepository.findByStatus(status, pageable)
                : mismatchRepository.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(ReconMismatchDTO::from);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private ReconMismatch getMismatch(Long id) {
        return mismatchRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Mismatch not found: " + id));
    }

    private void validateReport(Long reportId) {
        if (!reportRepository.existsById(reportId)) {
            throw new EntityNotFoundException("ReconReport not found: " + reportId);
        }
    }
}
