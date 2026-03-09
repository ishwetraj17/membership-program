package com.firstclub.billing.repository;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByUserId(Long userId);

    List<Invoice> findBySubscriptionId(Long subscriptionId);

    List<Invoice> findByStatus(InvoiceStatus status);

    Optional<Invoice> findTopBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId);

    /** Invoices created within [start, end) — used by reconciliation. */
    List<Invoice> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    // ── Phase 8: merchant-scoped queries ────────────────────────────────────
    List<Invoice> findByMerchantId(Long merchantId);

    Optional<Invoice> findByIdAndMerchantId(Long id, Long merchantId);

    // ── Phase 13: admin search ────────────────────────────────────────────
    Optional<Invoice> findByInvoiceNumberAndMerchantId(String invoiceNumber, Long merchantId);

    // ── Phase 11: integrity-check queries ────────────────────────────────────
    /** Distinct subscription IDs that have at least one invoice — used by
     *  SubscriptionInvoicePeriodOverlapChecker. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT DISTINCT i.subscriptionId FROM Invoice i WHERE i.subscriptionId IS NOT NULL")
    List<Long> findDistinctSubscriptionIds();
}
