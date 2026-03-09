package com.firstclub.payments.refund.service;

import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.refund.entity.RefundV2;

/**
 * Posts double-entry ledger reversals for refund events.
 *
 * <p><strong>MVP accounting policy (synchronous, single-account reversal):</strong>
 * Every refund, regardless of how much of the service has been delivered, is
 * reversed <em>entirely</em> against {@code SUBSCRIPTION_LIABILITY}:
 * <pre>
 *   DR SUBSCRIPTION_LIABILITY  refund.amount
 *   CR PG_CLEARING             refund.amount
 * </pre>
 * This is correct when the service has not yet been rendered (deferred revenue
 * is still sitting in the liability account).  When revenue has already been
 * recognised (moved DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS), the
 * reversal will temporarily create a negative balance in
 * {@code SUBSCRIPTION_LIABILITY}; a future reconciliation sweep or a more
 * sophisticated split policy should correct this.
 *
 * @see <a href="docs/refunds-and-reversals.md">Refunds &amp; Reversals runbook</a>
 */
public interface RefundAccountingService {

    /**
     * Post the reversal ledger entry for a completed refund.
     * Must be called within an active transaction.
     *
     * @param refund  the {@code refunds_v2} row being completed
     * @param payment the parent payment (used for currency)
     */
    void postRefundReversal(RefundV2 refund, Payment payment);
}
