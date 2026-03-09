package com.firstclub.payments.disputes.service.impl;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.service.DisputeAccountingService;
import com.firstclub.payments.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@inheritDoc}
 *
 * <p>All entries are balanced (DR == CR) and reference {@code LedgerReferenceType.DISPUTE}
 * with the dispute ID, making each accounting movement fully traceable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeAccountingServiceImpl implements DisputeAccountingService {

    private final LedgerService ledgerService;

    // ── OPEN: DR DISPUTE_RESERVE / CR PG_CLEARING ─────────────────────────────

    @Override
    public void postDisputeOpen(Dispute dispute, Payment payment) {
        log.debug("DISPUTE_OPENED ledger — disputeId={}, paymentId={}, amount={} {}",
                dispute.getId(), dispute.getPaymentId(),
                dispute.getAmount(), payment.getCurrency());
        ledgerService.postEntry(
                LedgerEntryType.DISPUTE_OPENED,
                LedgerReferenceType.DISPUTE,
                dispute.getId(),
                payment.getCurrency(),
                List.of(
                        LedgerLineRequest.builder()
                                .accountName("DISPUTE_RESERVE")
                                .direction(LineDirection.DEBIT)
                                .amount(dispute.getAmount())
                                .build(),
                        LedgerLineRequest.builder()
                                .accountName("PG_CLEARING")
                                .direction(LineDirection.CREDIT)
                                .amount(dispute.getAmount())
                                .build()
                )
        );
    }

    // ── WON: DR PG_CLEARING / CR DISPUTE_RESERVE ──────────────────────────────

    @Override
    public void postDisputeWon(Dispute dispute, Payment payment) {
        log.debug("DISPUTE_WON ledger — disputeId={}, paymentId={}, amount={} {}",
                dispute.getId(), dispute.getPaymentId(),
                dispute.getAmount(), payment.getCurrency());
        ledgerService.postEntry(
                LedgerEntryType.DISPUTE_WON,
                LedgerReferenceType.DISPUTE,
                dispute.getId(),
                payment.getCurrency(),
                List.of(
                        LedgerLineRequest.builder()
                                .accountName("PG_CLEARING")
                                .direction(LineDirection.DEBIT)
                                .amount(dispute.getAmount())
                                .build(),
                        LedgerLineRequest.builder()
                                .accountName("DISPUTE_RESERVE")
                                .direction(LineDirection.CREDIT)
                                .amount(dispute.getAmount())
                                .build()
                )
        );
    }

    // ── LOST: DR CHARGEBACK_EXPENSE / CR DISPUTE_RESERVE ─────────────────────

    @Override
    public void postDisputeLost(Dispute dispute, Payment payment) {
        log.debug("CHARGEBACK_POSTED ledger — disputeId={}, paymentId={}, amount={} {}",
                dispute.getId(), dispute.getPaymentId(),
                dispute.getAmount(), payment.getCurrency());
        ledgerService.postEntry(
                LedgerEntryType.CHARGEBACK_POSTED,
                LedgerReferenceType.DISPUTE,
                dispute.getId(),
                payment.getCurrency(),
                List.of(
                        LedgerLineRequest.builder()
                                .accountName("CHARGEBACK_EXPENSE")
                                .direction(LineDirection.DEBIT)
                                .amount(dispute.getAmount())
                                .build(),
                        LedgerLineRequest.builder()
                                .accountName("DISPUTE_RESERVE")
                                .direction(LineDirection.CREDIT)
                                .amount(dispute.getAmount())
                                .build()
                )
        );
    }
}
