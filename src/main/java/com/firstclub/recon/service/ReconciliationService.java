package com.firstclub.recon.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentIntent;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentIntentRepository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.dto.ReconReportDTO;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconReport;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.ReconReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Nightly reconciliation: compares expected invoice totals with
 * actual captured payments for a given business day, producing a
 * {@link ReconReport} with itemised {@link ReconMismatch} rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final InvoiceRepository        invoiceRepository;
    private final PaymentRepository        paymentRepository;
    private final PaymentIntentRepository  paymentIntentRepository;
    private final ReconReportRepository    reconReportRepository;
    private final ReconMismatchRepository  reconMismatchRepository;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Run (or re-run) reconciliation for {@code reportDate}.
     * Idempotent: if a report already exists for that date it is overwritten.
     */
    @Transactional
    public ReconReportDTO runForDate(LocalDate reportDate) {
        LocalDateTime dayStart = reportDate.atStartOfDay();
        LocalDateTime dayEnd   = reportDate.atTime(LocalTime.MAX);

        // 1. Load invoices created on this date
        List<Invoice> invoices = invoiceRepository.findByCreatedAtBetween(dayStart, dayEnd);

        // 2. Load captured payments settled on this date
        List<Payment> capturedPayments =
                paymentRepository.findByStatusAndCapturedAtBetween(
                        PaymentStatus.CAPTURED, dayStart, dayEnd);

        // 3. Build helper maps
        //    invoiceId → list of captured payments (via PaymentIntent.invoiceId)
        Map<Long, List<Payment>> invoiceToPayments = buildInvoiceToPayments(invoices, capturedPayments);

        //    paymentId → invoiceId (null if the PI has no invoice)
        Map<Long, Long> paymentToInvoice = buildPaymentToInvoice(capturedPayments);

        // 4. Compute totals
        BigDecimal expectedTotal = invoices.stream()
                .map(Invoice::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal actualTotal = capturedPayments.stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 5. Detect mismatches
        List<ReconMismatch> mismatches = new ArrayList<>();

        // a) Invoice without any captured payment
        for (Invoice inv : invoices) {
            List<Payment> linked = invoiceToPayments.getOrDefault(inv.getId(), List.of());
            if (linked.isEmpty()) {
                mismatches.add(mismatch(MismatchType.INVOICE_NO_PAYMENT,
                        inv.getId(), null,
                        "Invoice " + inv.getId() + " (amount=" + inv.getTotalAmount()
                                + ") has no captured payment"));
            }
        }

        // b) Payment with no invoice on this date
        Set<Long> invoiceIds = invoices.stream().map(Invoice::getId).collect(Collectors.toSet());
        for (Payment pmt : capturedPayments) {
            Long linkedInvoiceId = paymentToInvoice.get(pmt.getId());
            if (linkedInvoiceId == null || !invoiceIds.contains(linkedInvoiceId)) {
                mismatches.add(mismatch(MismatchType.PAYMENT_NO_INVOICE,
                        null, pmt.getId(),
                        "Payment " + pmt.getId() + " (gatewayTxnId=" + pmt.getGatewayTxnId()
                                + ") has no matching invoice on " + reportDate));
            }
        }

        // c) Amount mismatch: invoice total ≠ sum of linked payments
        for (Invoice inv : invoices) {
            List<Payment> linked = invoiceToPayments.getOrDefault(inv.getId(), List.of());
            if (!linked.isEmpty()) {
                BigDecimal pmtTotal = linked.stream()
                        .map(Payment::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (pmtTotal.compareTo(inv.getTotalAmount()) != 0) {
                    mismatches.add(mismatch(MismatchType.AMOUNT_MISMATCH,
                            inv.getId(), linked.get(0).getId(),
                            "Invoice " + inv.getId() + " total=" + inv.getTotalAmount()
                                    + " but payment total=" + pmtTotal));
                }
            }
        }

        // d) Duplicate gateway transaction IDs among all captured payments
        Map<String, List<Payment>> byTxnId = capturedPayments.stream()
                .collect(Collectors.groupingBy(Payment::getGatewayTxnId));
        for (Map.Entry<String, List<Payment>> entry : byTxnId.entrySet()) {
            if (entry.getValue().size() > 1) {
                entry.getValue().forEach(dup ->
                        mismatches.add(mismatch(MismatchType.DUPLICATE_GATEWAY_TXN,
                                null, dup.getId(),
                                "Duplicate gatewayTxnId=" + entry.getKey())));
            }
        }

        // 6. Persist report (upsert by date).
        //
        // Guard: BusinessLockScope.RECON_REPORT_UPSERT
        // We acquire a PESSIMISTIC_WRITE lock on the existing report row (if any)
        // before reading it.  This serializes concurrent runs for the same date:
        //   - Run A acquires lock → deletes + re-inserts mismatches → commits → releases lock
        //   - Run B was blocked on the lock → acquires it after A commits → overwrites with its
        //     own result (acceptable idempotent re-run semantics)
        // Without this lock, A and B can interleave their deleteAll + saveAll cycles,
        // leaving the mismatch table in a partially-overwritten state.
        ReconReport report = reconReportRepository.findByReportDateForUpdate(reportDate)
                .orElse(ReconReport.builder().reportDate(reportDate).build());

        report.setExpectedTotal(expectedTotal);
        report.setActualTotal(actualTotal);
        report.setMismatchCount(mismatches.size());
        report = reconReportRepository.save(report);

        // Delete old mismatches if this is a re-run
        reconMismatchRepository.deleteAll(reconMismatchRepository.findByReportId(report.getId()));

        final Long reportId = report.getId();
        mismatches.forEach(m -> m.setReportId(reportId));
        List<ReconMismatch> saved = reconMismatchRepository.saveAll(mismatches);

        log.info("Recon for {}: expected={} actual={} mismatches={}",
                reportDate, expectedTotal, actualTotal, saved.size());

        return toDTO(report, saved);
    }

    /**
     * Load an existing report for {@code reportDate} without re-running reconciliation.
     * Returns {@code null} when no report exists yet.
     */
    @Transactional(readOnly = true)
    public ReconReportDTO getForDate(LocalDate reportDate) {
        return reconReportRepository.findByReportDate(reportDate)
                .map(report -> toDTO(report, reconMismatchRepository.findByReportId(report.getId())))
                .orElse(null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<Long, List<Payment>> buildInvoiceToPayments(
            List<Invoice> invoices, List<Payment> payments) {

        // paymentIntentId → payment
        Map<Long, List<Payment>> byIntentId = payments.stream()
                .collect(Collectors.groupingBy(Payment::getPaymentIntentId));

        Map<Long, List<Payment>> result = new HashMap<>();
        for (Invoice inv : invoices) {
            List<PaymentIntent> intents = paymentIntentRepository.findByInvoiceId(inv.getId());
            List<Payment> linked = intents.stream()
                    .flatMap(pi -> byIntentId.getOrDefault(pi.getId(), List.of()).stream())
                    .collect(Collectors.toList());
            result.put(inv.getId(), linked);
        }
        return result;
    }

    private Map<Long, Long> buildPaymentToInvoice(List<Payment> payments) {
        Map<Long, Long> result = new HashMap<>();
        for (Payment pmt : payments) {
            paymentIntentRepository.findById(pmt.getPaymentIntentId())
                    .ifPresent(pi -> result.put(pmt.getId(), pi.getInvoiceId()));
        }
        return result;
    }

    private static ReconMismatch mismatch(MismatchType type, Long invoiceId, Long paymentId, String details) {
        return ReconMismatch.builder()
                .type(type)
                .invoiceId(invoiceId)
                .paymentId(paymentId)
                .details(details)
                .build();
    }

    private static ReconReportDTO toDTO(ReconReport report, List<ReconMismatch> mismatches) {
        return ReconReportDTO.from(report, mismatches);
    }
}
