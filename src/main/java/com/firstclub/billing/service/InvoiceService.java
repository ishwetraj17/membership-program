package com.firstclub.billing.service;

import com.firstclub.billing.dto.InvoiceDTO;
import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.entity.*;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.*;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.SubscriptionHistoryRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.platform.statemachine.StateMachineValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core billing service.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create invoices and invoice lines for subscription periods.</li>
 *   <li>Apply available credit-note balances to open invoices.</li>
 *   <li>Mark invoices PAID and activate the associated subscription when a
 *       payment succeeds (called by the webhook processor).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private final InvoiceRepository         invoiceRepository;
    private final InvoiceLineRepository     invoiceLineRepository;
    private final CreditNoteRepository      creditNoteRepository;
    private final SubscriptionRepository    subscriptionRepository;
    private final SubscriptionHistoryRepository historyRepository;
    private final MembershipPlanRepository  planRepository;
    private final StateMachineValidator     stateMachineValidator;
    private final OutboxService             outboxService;
    private final DomainEventLog            domainEventLog;
    private final InvoiceTotalService       invoiceTotalService;

    /** Injected lazily to avoid circular-dependency issues between billing and ledger. */
    @Autowired @Lazy
    private RevenueRecognitionScheduleService recognitionScheduleService;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Creates an OPEN invoice for the given subscription period.
     *
     * <ul>
     *   <li>One {@code PLAN_CHARGE} line is added for the plan price.</li>
     *   <li>{@link #applyAvailableCredits(Long, Long)} is called automatically
     *       to apply any wallet balance the user holds.</li>
     * </ul>
     *
     * @return the created and credit-adjusted {@link InvoiceDTO}
     */
    @Transactional
    public InvoiceDTO createInvoiceForSubscription(
            Long userId,
            Long subscriptionId,
            Long planId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd) {

        MembershipPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new MembershipException("Plan not found: " + planId, "PLAN_NOT_FOUND"));

        // Persist the invoice skeleton
        Invoice invoice = Invoice.builder()
                .userId(userId)
                .subscriptionId(subscriptionId)
                .status(InvoiceStatus.OPEN)
                .currency("INR")
                .totalAmount(plan.getPrice())
                .dueDate(periodEnd)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .build();
        invoice = invoiceRepository.save(invoice);

        // Plan charge line
        InvoiceLine planLine = InvoiceLine.builder()
                .invoiceId(invoice.getId())
                .lineType(InvoiceLineType.PLAN_CHARGE)
                .description("Subscription: " + plan.getName())
                .amount(plan.getPrice())
                .build();
        invoiceLineRepository.save(planLine);

        // Re-compute total from lines (idempotent if no credits)
        invoice = recomputeTotal(invoice);

        // Apply any available wallet credits
        invoice = applyAvailableCredits(userId, invoice);

        InvoiceDTO dto = toDto(invoice);

        // Append-only domain event log (immutable audit trail)
        domainEventLog.record("INVOICE_CREATED", java.util.Map.of(
                "invoiceId",      dto.getId(),
                "userId",         dto.getUserId(),
                "subscriptionId", dto.getSubscriptionId() != null ? dto.getSubscriptionId() : 0L,
                "totalAmount",    dto.getTotalAmount().toPlainString(),
                "currency",       dto.getCurrency()));

        // Publish outbox event in the same transaction
        outboxService.publish(DomainEventTypes.INVOICE_CREATED, java.util.Map.of(
                "invoiceId",      dto.getId(),
                "userId",         dto.getUserId(),
                "subscriptionId", dto.getSubscriptionId() != null ? dto.getSubscriptionId() : 0L,
                "totalAmount",    dto.getTotalAmount().toPlainString(),
                "currency",       dto.getCurrency()));

        return dto;
    }

    // -------------------------------------------------------------------------
    // Credit application
    // -------------------------------------------------------------------------

    /**
     * Applies available credit-note balances (FIFO order) against an open invoice.
     *
     * <p>For each credit note with a remaining balance, a {@link InvoiceLineType#CREDIT_APPLIED}
     * line (negative amount) is appended and {@code credit_notes.used_amount} is updated.
     * The invoice's {@code total_amount} is recomputed after all credits are applied.
     *
     * @param userId    owner of the credit notes
     * @param invoiceId target invoice (must be OPEN)
     * @return the updated invoice DTO after credit application
     */
    @Transactional
    public InvoiceDTO applyAvailableCredits(Long userId, Long invoiceId) {
        Invoice invoice = fetchOpenInvoice(invoiceId);
        invoice = applyAvailableCredits(userId, invoice);
        return toDto(invoice);
    }

    // -------------------------------------------------------------------------
    // Payment succeeded hook (called by WebhookProcessingService)
    // -------------------------------------------------------------------------

    /**
     * Marks the invoice PAID and activates the linked subscription.
     *
     * <p>Idempotent: if the invoice is already PAID, returns silently.
     */
    @Transactional
    public void onPaymentSucceeded(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new MembershipException(
                        "Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND"));

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            log.debug("Invoice {} already PAID, skipping.", invoiceId);
            return;
        }

        // Validate transition OPEN → PAID
        stateMachineValidator.validate("INVOICE", invoice.getStatus(), InvoiceStatus.PAID);
        invoice.setStatus(InvoiceStatus.PAID);
        invoiceRepository.save(invoice);
        log.info("Invoice {} marked PAID", invoiceId);

        // Append-only domain event log
        domainEventLog.record("PAYMENT_SUCCEEDED", java.util.Map.of(
                "invoiceId",      invoiceId,
                "subscriptionId", invoice.getSubscriptionId() != null ? invoice.getSubscriptionId() : 0L,
                "amount",         invoice.getTotalAmount().toPlainString(),
                "currency",       invoice.getCurrency()));

        // Publish PAYMENT_SUCCEEDED in the same transaction
        outboxService.publish(DomainEventTypes.PAYMENT_SUCCEEDED, java.util.Map.of(
                "invoiceId",      invoiceId,
                "subscriptionId", invoice.getSubscriptionId() != null ? invoice.getSubscriptionId() : 0L,
                "amount",         invoice.getTotalAmount().toPlainString(),
                "currency",       invoice.getCurrency()));

        // Activate the subscription (if any)
        if (invoice.getSubscriptionId() != null) {
            activateSubscription(invoice.getSubscriptionId());
        }

        // Generate revenue recognition schedule — non-critical, must not fail the payment flow
        try {
            recognitionScheduleService.generateScheduleForInvoice(invoiceId);
        } catch (Exception e) {
            log.warn("Revenue recognition schedule generation failed for invoice {} — will not block payment: {}",
                    invoiceId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public InvoiceDTO findById(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new MembershipException(
                        "Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND"));
        return toDto(invoice);
    }

    @Transactional(readOnly = true)
    public List<InvoiceDTO> findByUserId(Long userId) {
        return invoiceRepository.findByUserId(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Invoice applyAvailableCredits(Long userId, Invoice invoice) {
        BigDecimal remaining = invoice.getTotalAmount();
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return invoice;
        }

        List<CreditNote> credits = creditNoteRepository.findAvailableByUserId(userId);
        for (CreditNote credit : credits) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = credit.getAvailableBalance();
            BigDecimal apply     = available.min(remaining);

            InvoiceLine creditLine = InvoiceLine.builder()
                    .invoiceId(invoice.getId())
                    .lineType(InvoiceLineType.CREDIT_APPLIED)
                    .description("Credit note #" + credit.getId() + " applied")
                    .amount(apply.negate())  // negative = reduces amount due
                    .build();
            invoiceLineRepository.save(creditLine);

            credit.setUsedAmount(credit.getUsedAmount().add(apply));
            creditNoteRepository.save(credit);

            remaining = remaining.subtract(apply);
            log.debug("Applied {} from credit note {} to invoice {}", apply, credit.getId(), invoice.getId());
        }

        return recomputeTotal(invoice);
    }

    private Invoice recomputeTotal(Invoice invoice) {
        // Delegate to InvoiceTotalService which populates all breakdown fields
        // (subtotal, discountTotal, creditTotal, taxTotal, grandTotal) and keeps
        // totalAmount in sync for backward compatibility.
        return invoiceTotalService.recomputeTotals(invoice);
    }

    private void activateSubscription(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription not found: " + subscriptionId, "SUBSCRIPTION_NOT_FOUND"));

        if (sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE) {
            log.debug("Subscription {} already ACTIVE, skipping.", subscriptionId);
            return;
        }

        Subscription.SubscriptionStatus oldStatus = sub.getStatus();
        stateMachineValidator.validate("SUBSCRIPTION", oldStatus, Subscription.SubscriptionStatus.ACTIVE);
        sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(sub);

        SubscriptionHistory history = SubscriptionHistory.builder()
                .subscription(sub)
                .eventType(SubscriptionHistory.EventType.PAYMENT_SUCCEEDED)
                .oldStatus(oldStatus)
                .newStatus(Subscription.SubscriptionStatus.ACTIVE)
                .newPlanId(sub.getPlan().getId())
                .reason("Payment received — subscription activated")
                .build();
        historyRepository.save(history);

        // Append-only domain event log
        domainEventLog.record("SUBSCRIPTION_ACTIVATED", java.util.Map.of(
                "subscriptionId", subscriptionId,
                "userId",         sub.getUser() != null ? sub.getUser().getId() : 0L));

        // Publish SUBSCRIPTION_ACTIVATED in the same transaction
        outboxService.publish(DomainEventTypes.SUBSCRIPTION_ACTIVATED, java.util.Map.of(
                "subscriptionId", subscriptionId,
                "userId",         sub.getUser() != null ? sub.getUser().getId() : 0L));

        log.info("Subscription {} activated after payment (was {})", subscriptionId, oldStatus);
    }

    private Invoice fetchOpenInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new MembershipException(
                        "Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND"));
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new MembershipException(
                    "Invoice " + invoiceId + " is not OPEN (status=" + invoice.getStatus() + ")",
                    "INVOICE_NOT_OPEN");
        }
        return invoice;
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private InvoiceDTO toDto(Invoice invoice) {
        List<InvoiceLineDTO> lineDtos = invoiceLineRepository
                .findByInvoiceId(invoice.getId())
                .stream()
                .map(l -> InvoiceLineDTO.builder()
                        .id(l.getId())
                        .invoiceId(l.getInvoiceId())
                        .lineType(l.getLineType())
                        .description(l.getDescription())
                        .amount(l.getAmount())
                        .build())
                .collect(Collectors.toList());

        return InvoiceDTO.builder()
                .id(invoice.getId())
                .userId(invoice.getUserId())
                .subscriptionId(invoice.getSubscriptionId())
                .status(invoice.getStatus())
                .currency(invoice.getCurrency())
                .totalAmount(invoice.getTotalAmount())
                .dueDate(invoice.getDueDate())
                .periodStart(invoice.getPeriodStart())
                .periodEnd(invoice.getPeriodEnd())
                .createdAt(invoice.getCreatedAt())
                .updatedAt(invoice.getUpdatedAt())
                .merchantId(invoice.getMerchantId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .subtotal(invoice.getSubtotal())
                .discountTotal(invoice.getDiscountTotal())
                .creditTotal(invoice.getCreditTotal())
                .taxTotal(invoice.getTaxTotal())
                .grandTotal(invoice.getGrandTotal())
                .lines(lineDtos)
                .build();
    }
}
