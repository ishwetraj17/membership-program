package com.firstclub.payments.service;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.payments.dto.RefundRequestDTO;
import com.firstclub.payments.dto.RefundResponseDTO;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.entity.Refund;
import com.firstclub.payments.entity.RefundStatus;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.payments.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository  refundRepository;
    private final LedgerService     ledgerService;
    private final OutboxService     outboxService;
    private final DomainEventLog    domainEventLog;

    @Transactional
    public RefundResponseDTO createRefund(RefundRequestDTO request) {
        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new MembershipException(
                        "Payment not found: " + request.getPaymentId(),
                        "PAYMENT_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new MembershipException(
                    "Payment " + request.getPaymentId() + " is not in CAPTURED state",
                    "PAYMENT_NOT_CAPTURED");
        }

        if (request.getAmount().compareTo(payment.getAmount()) > 0) {
            throw new MembershipException(
                    "Refund amount exceeds original payment amount",
                    "REFUND_AMOUNT_EXCEEDS_PAYMENT");
        }

        Refund refund = refundRepository.save(Refund.builder()
                .paymentId(payment.getId())
                .amount(request.getAmount())
                .currency(payment.getCurrency())
                .reason(request.getReason())
                .status(RefundStatus.COMPLETED)
                .build());

        // Post REFUND_ISSUED reversal: DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING
        ledgerService.postEntry(
                LedgerEntryType.REFUND_ISSUED,
                LedgerReferenceType.REFUND,
                refund.getId(),
                payment.getCurrency(),
                List.of(
                        LedgerLineRequest.builder()
                                .accountName("SUBSCRIPTION_LIABILITY")
                                .direction(LineDirection.DEBIT)
                                .amount(request.getAmount())
                                .build(),
                        LedgerLineRequest.builder()
                                .accountName("PG_CLEARING")
                                .direction(LineDirection.CREDIT)
                                .amount(request.getAmount())
                                .build()
                )
        );

        log.info("Refund {} created for payment {} amount={}", refund.getId(), payment.getId(), request.getAmount());

        // Append-only domain event log (immutable audit trail)
        domainEventLog.record("REFUND_ISSUED", java.util.Map.of(
                "refundId",  refund.getId(),
                "paymentId", refund.getPaymentId(),
                "amount",    refund.getAmount().toPlainString(),
                "currency",  refund.getCurrency()));

        // Publish REFUND_ISSUED in the same transaction
        outboxService.publish(DomainEventTypes.REFUND_ISSUED, java.util.Map.of(
                "refundId",  refund.getId(),
                "paymentId", refund.getPaymentId(),
                "amount",    refund.getAmount().toPlainString(),
                "currency",  refund.getCurrency()));

        return toDto(refund);
    }

    private RefundResponseDTO toDto(Refund refund) {
        return RefundResponseDTO.builder()
                .id(refund.getId())
                .paymentId(refund.getPaymentId())
                .amount(refund.getAmount())
                .currency(refund.getCurrency())
                .reason(refund.getReason())
                .status(refund.getStatus())
                .createdAt(refund.getCreatedAt())
                .build();
    }
}
