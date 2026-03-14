package com.firstclub.ledger.revenue.service.impl;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.RevenueScheduleAllocator;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RevenueRecognitionScheduleServiceImpl implements RevenueRecognitionScheduleService {

    private final RevenueRecognitionScheduleRepository scheduleRepository;
    private final InvoiceRepository                   invoiceRepository;
    private final RevenueScheduleAllocator             allocator;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<RevenueRecognitionScheduleResponseDTO> generateScheduleForInvoice(Long invoiceId) {
        // Idempotency — return existing schedules if already generated
        if (scheduleRepository.existsByInvoiceId(invoiceId)) {
            log.debug("Revenue recognition schedule already exists for invoice {}, skipping generation", invoiceId);
            return scheduleRepository.findByInvoiceId(invoiceId).stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
        }

        Invoice invoice = loadAndValidateInvoice(invoiceId);
        if (invoice == null) return List.of();

        LocalDate startDate = invoice.getPeriodStart().toLocalDate();
        LocalDate endDate   = invoice.getPeriodEnd().toLocalDate();
        long numDays = ChronoUnit.DAYS.between(startDate, endDate);
        validatePeriod(invoiceId, numDays, startDate, endDate);

        BigDecimal recognizableAmount = resolveRecognizableAmount(invoice);
        if (recognizableAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Invoice {} has zero recognizable amount; skipping schedule generation", invoiceId);
            return List.of();
        }

        String fingerprint = computeFingerprint(invoice);

        // Secondary fingerprint idempotency check: guards against concurrent
        // callers that both pass existsByInvoiceId (race before first commit).
        if (scheduleRepository.existsByGenerationFingerprint(fingerprint)) {
            log.debug("Schedule with fingerprint {} already exists for invoice {}, returning existing", fingerprint, invoiceId);
            return scheduleRepository.findByInvoiceId(invoiceId).stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
        }

        List<RevenueRecognitionSchedule> schedules = allocator.allocate(
                invoice.getId(), invoice.getMerchantId(), invoice.getSubscriptionId(),
                recognizableAmount,
                invoice.getCurrency() != null ? invoice.getCurrency() : "INR",
                startDate, endDate, fingerprint, false);

        scheduleRepository.saveAll(schedules);
        log.info("Generated {} revenue recognition rows for invoice {} (merchant {}, subscription {}, fingerprint {})",
                schedules.size(), invoiceId, invoice.getMerchantId(), invoice.getSubscriptionId(), fingerprint);

        return schedules.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<RevenueRecognitionScheduleResponseDTO> regenerateScheduleForInvoice(Long invoiceId) {
        // Delete all PENDING rows — POSTED rows are immutable financial records and must not be touched
        List<RevenueRecognitionSchedule> pendingRows = scheduleRepository
                .findByInvoiceIdAndStatus(invoiceId, RevenueRecognitionStatus.PENDING);
        if (!pendingRows.isEmpty()) {
            scheduleRepository.deleteAll(pendingRows);
            log.info("Force-regeneration: deleted {} PENDING schedule rows for invoice {}",
                    pendingRows.size(), invoiceId);
        }

        Invoice invoice = loadAndValidateInvoice(invoiceId);
        if (invoice == null) return List.of();

        LocalDate startDate = invoice.getPeriodStart().toLocalDate();
        LocalDate endDate   = invoice.getPeriodEnd().toLocalDate();
        long numDays = ChronoUnit.DAYS.between(startDate, endDate);
        validatePeriod(invoiceId, numDays, startDate, endDate);

        BigDecimal recognizableAmount = resolveRecognizableAmount(invoice);
        if (recognizableAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Invoice {} has zero recognizable amount; no rows generated in catch-up run", invoiceId);
            return List.of();
        }

        String fingerprint = computeFingerprint(invoice);

        List<RevenueRecognitionSchedule> schedules = allocator.allocate(
                invoice.getId(), invoice.getMerchantId(), invoice.getSubscriptionId(),
                recognizableAmount,
                invoice.getCurrency() != null ? invoice.getCurrency() : "INR",
                startDate, endDate, fingerprint, true);

        scheduleRepository.saveAll(schedules);
        log.info("Catch-up regeneration: created {} revenue recognition rows for invoice {} (fingerprint {})",
                schedules.size(), invoiceId, fingerprint);

        return schedules.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevenueRecognitionScheduleResponseDTO> listAllSchedules() {
        return scheduleRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevenueRecognitionScheduleResponseDTO> listSchedulesByInvoice(Long invoiceId) {
        return scheduleRepository.findByInvoiceId(invoiceId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Loads and validates invoice for schedule generation.
     * Returns {@code null} when the invoice has no subscription (caller should return empty list).
     * Throws {@link MembershipException} when the invoice is not found or lacks period boundaries.
     */
    private Invoice loadAndValidateInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new MembershipException(
                        "Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        if (invoice.getSubscriptionId() == null) {
            log.debug("Invoice {} has no subscription; skipping revenue recognition schedule", invoiceId);
            return null;
        }

        if (invoice.getPeriodStart() == null || invoice.getPeriodEnd() == null) {
            throw new MembershipException(
                    "Invoice " + invoiceId + " has no service period (periodStart/periodEnd); " +
                    "cannot generate revenue recognition schedule",
                    "INVALID_SERVICE_PERIOD",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        return invoice;
    }

    private void validatePeriod(Long invoiceId, long numDays, LocalDate start, LocalDate end) {
        if (numDays <= 0) {
            throw new MembershipException(
                    "Service period for invoice " + invoiceId + " must be at least 1 day " +
                    "(got " + numDays + " days between " + start + " and " + end + ")",
                    "INVALID_SERVICE_PERIOD",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /**
     * Resolves the total amount that should be recognized over the service period.
     * Uses grandTotal if set and positive; falls back to legacy totalAmount.
     */
    private BigDecimal resolveRecognizableAmount(Invoice invoice) {
        BigDecimal grand = invoice.getGrandTotal();
        if (grand != null && grand.compareTo(BigDecimal.ZERO) > 0) {
            return grand;
        }
        BigDecimal legacy = invoice.getTotalAmount();
        return legacy != null ? legacy : BigDecimal.ZERO;
    }

    /**
     * Computes a SHA-256 fingerprint of the invoice's generation inputs.
     * Format: {@code invoiceId:subscriptionId:grandTotal:periodStart:periodEnd}
     *
     * <p>Same fingerprint means the same invoice parameters were used → safe to
     * treat as an idempotent re-submission rather than a duplicate.
     */
    public String computeFingerprint(Invoice invoice) {
        BigDecimal amount = resolveRecognizableAmount(invoice);
        String input = invoice.getId() + ":"
                + invoice.getSubscriptionId() + ":"
                + amount.toPlainString() + ":"
                + invoice.getPeriodStart() + ":"
                + invoice.getPeriodEnd();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in the JDK — this branch is unreachable
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private RevenueRecognitionScheduleResponseDTO toDto(RevenueRecognitionSchedule s) {
        return RevenueRecognitionScheduleResponseDTO.builder()
                .id(s.getId())
                .merchantId(s.getMerchantId())
                .subscriptionId(s.getSubscriptionId())
                .invoiceId(s.getInvoiceId())
                .recognitionDate(s.getRecognitionDate())
                .amount(s.getAmount())
                .currency(s.getCurrency())
                .status(s.getStatus())
                .ledgerEntryId(s.getLedgerEntryId())
                .generationFingerprint(s.getGenerationFingerprint())
                .postingRunId(s.getPostingRunId())
                .catchUpRun(s.isCatchUpRun())
                .expectedAmountMinor(s.getExpectedAmountMinor())
                .recognizedAmountMinor(s.getRecognizedAmountMinor())
                .roundingAdjustmentMinor(s.getRoundingAdjustmentMinor())
                .policyCode(s.getPolicyCode())
                .guardDecision(s.getGuardDecision())
                .guardReason(s.getGuardReason())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
