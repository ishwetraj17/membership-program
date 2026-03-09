package com.firstclub.payments.refund.service.impl;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.service.RefundAccountingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Posts the double-entry ledger reversal when a V2 refund is completed.
 *
 * <p><strong>Accounting policy — MVP single-account reversal:</strong>
 * <pre>
 *   DR SUBSCRIPTION_LIABILITY  refund.amount   (reduce deferred revenue)
 *   CR PG_CLEARING             refund.amount   (reduce gateway receivable)
 * </pre>
 *
 * <p>This policy is exact when the subscription service has <em>not yet</em>
 * been delivered (full amount still in deferred revenue).  When some or all of
 * the subscription period has elapsed and revenue has already been recognised
 * ({@code DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS}), the reversal
 * will temporarily drive {@code SUBSCRIPTION_LIABILITY} negative.  A future
 * enhancement should split the reversal between {@code SUBSCRIPTION_LIABILITY}
 * and {@code REVENUE_SUBSCRIPTIONS} proportionally to the earned/unearned split.
 * See {@code docs/refunds-and-reversals.md} for the full policy discussion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundAccountingServiceImpl implements RefundAccountingService {

    private final LedgerService ledgerService;

    @Override
    public void postRefundReversal(RefundV2 refund, Payment payment) {
        log.debug("Posting REFUND_ISSUED ledger entry for refund {} (payment {}, amount={} {})",
                refund.getId(), refund.getPaymentId(), refund.getAmount(), payment.getCurrency());

        ledgerService.postEntry(
                LedgerEntryType.REFUND_ISSUED,
                LedgerReferenceType.REFUND,
                refund.getId(),
                payment.getCurrency(),
                List.of(
                        LedgerLineRequest.builder()
                                .accountName("SUBSCRIPTION_LIABILITY")
                                .direction(LineDirection.DEBIT)
                                .amount(refund.getAmount())
                                .build(),
                        LedgerLineRequest.builder()
                                .accountName("PG_CLEARING")
                                .direction(LineDirection.CREDIT)
                                .amount(refund.getAmount())
                                .build()
                )
        );
    }
}
