package com.firstclub.payments.disputes.service;

import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.entity.Payment;

/**
 * Posts double-entry ledger entries for dispute lifecycle events.
 *
 * <h3>Accounting policy (MVP)</h3>
 * <pre>
 * ON OPEN  (dispute created):
 *   DR DISPUTE_RESERVE   dispute.amount   — freeze funds in a dedicated ASSET account
 *   CR PG_CLEARING       dispute.amount   — reduce gateway clearing balance
 *
 * ON WON   (merchant wins — funds returned):
 *   DR PG_CLEARING       dispute.amount   — return money to gateway clearing
 *   CR DISPUTE_RESERVE   dispute.amount   — clear the reserve
 *
 * ON LOST  (chargeback — funds leave permanently):
 *   DR CHARGEBACK_EXPENSE  dispute.amount — record the loss as an EXPENSE
 *   CR DISPUTE_RESERVE     dispute.amount — clear the reserve
 * </pre>
 *
 * <p>All entries use {@code LedgerReferenceType.DISPUTE} and reference the dispute ID,
 * providing a complete audit trail of every accounting movement.
 *
 * <p>Known limitation (MVP): entries are merchant-level only. Platform-level
 * gateway fee recovery on chargebacks is out of scope.
 */
public interface DisputeAccountingService {

    /** Post DR DISPUTE_RESERVE / CR PG_CLEARING when a dispute is opened. */
    void postDisputeOpen(Dispute dispute, Payment payment);

    /** Post DR PG_CLEARING / CR DISPUTE_RESERVE when a dispute is won. */
    void postDisputeWon(Dispute dispute, Payment payment);

    /** Post DR CHARGEBACK_EXPENSE / CR DISPUTE_RESERVE when a dispute is lost. */
    void postDisputeLost(Dispute dispute, Payment payment);
}
