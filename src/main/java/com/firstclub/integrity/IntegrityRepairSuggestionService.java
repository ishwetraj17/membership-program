package com.firstclub.integrity;

import com.firstclub.integrity.checkers.*;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Provides default human-readable repair suggestions for each invariant.
 *
 * <p>Suggestions are keyed by the invariant's {@link InvariantChecker#getName()} constant
 * and are used by {@link com.firstclub.integrity.IntegrityCheckService} when persisting
 * results, and exposed via the admin API.
 */
@Service
public class IntegrityRepairSuggestionService {

    private static final Map<String, String> SUGGESTIONS = Map.ofEntries(

        Map.entry(BalanceSheetEquationChecker.NAME,
                "Sum all DEBIT and CREDIT ledger lines. If debits != credits the journal is unbalanced. "
                + "Identify the entry whose lines do not balance (use findUnbalancedEntries query) and "
                + "post a correcting entry to zero the difference."),

        Map.entry(DeferredRevenueNonNegativeChecker.NAME,
                "The SUBSCRIPTION_LIABILITY account has a net debit balance, meaning more revenue was "
                + "recognized than was deferred. Audit REVENUE_RECOGNITION entries for the period and "
                + "identify any entries that were posted without a corresponding deferral. Post a "
                + "correcting deferral entry."),

        Map.entry(RevenueRecognitionCeilingChecker.NAME,
                "The sum of recognition schedule entries exceeds the invoice grand total. Identify "
                + "the schedule rows whose amounts sum above the ceiling. Void the excess row(s) and "
                + "post a correcting REVENUE_REVERSAL ledger entry for the difference."),

        Map.entry(PaymentHasSingleLedgerEntryChecker.NAME,
                "For 0-entry gaps: manually post a PAYMENT_CAPTURED ledger entry or trigger "
                + "PaymentSucceededHandler. For 2+ duplicate entries: identify the duplicate "
                + "via createdAt and reversalOfEntryId, then post a reversal for the extra entry."),

        Map.entry(RefundAmountChainChecker.NAME,
                "The payment's refundedAmount + disputedAmount exceeds capturedAmount. "
                + "Verify each refund and dispute record attached to the payment. If a refund "
                + "was double-recorded, void the duplicate RefundRecord and reverse the "
                + "corresponding ledger entry."),

        Map.entry(RecognitionScheduleCompletenessChecker.NAME,
                "A PAID subscription invoice has no revenue recognition schedule. "
                + "Call RevenueRecognitionScheduleService.generateSchedule(invoiceId) to create "
                + "the missing schedule and backfill recognition entries for elapsed periods."),

        Map.entry(NoFutureLedgerEntryChecker.NAME,
                "A ledger entry has a createdAt timestamp in the future, indicating a clock skew "
                + "or a manual insertion error. Correct the timestamp via a direct DB update "
                + "(requires change-control approval) and investigate the source system's clock."),

        Map.entry(InvoicePaymentAmountConsistencyChecker.NAME,
                "Re-compute the invoice totals via InvoiceTotalService.recalculate(invoiceId) and "
                + "compare with the stored grandTotal. If the recalculated value differs, update "
                + "the invoice and issue a corrected PDF / credit note as required."),

        Map.entry(DisputeReserveCompletenessChecker.NAME,
                "For each dispute with reservePosted=false: call DisputeAccountingService.postReserve(disputeId) "
                + "inside a transaction. Confirm DISPUTE_RESERVE ledger entries are created and set "
                + "reservePosted=true on the dispute."),

        Map.entry(SettlementLedgerCompletenessChecker.NAME,
                "For each un-referenced SETTLEMENT entry: look up the gateway payout ID in the entry's "
                + "createdAt timestamp window and recreate the SettlementBatch row, then set "
                + "referenceType=SETTLEMENT_BATCH / referenceId on the ledger entry."),

        Map.entry(AssetAccountNonNegativeChecker.NAME,
                "Investigate all credit entries posted to the affected ASSET account. Common causes: "
                + "a refund was posted to the receivables account instead of the refund payable account, "
                + "or a reversal entry was applied twice. Post a correcting debit journal entry."),

        Map.entry(NoOrphanLedgerLineChecker.NAME,
                "For each orphan LedgerLine: verify whether the parent LedgerEntry existed and was "
                + "deleted (recover from backup or event log) or was never created (re-run the posting "
                + "service for the original event). After restoring the parent, re-enable FK constraint."),

        Map.entry(OutboxToLedgerGapChecker.NAME,
                "For each FAILED outbox event: inspect the payload and determine whether the downstream "
                + "ledger-posting consumer received the event via another path. If not, re-publish via "
                + "OutboxService.retry(eventId) and confirm the ledger entry is created."),

        Map.entry(WebhookDuplicateProcessingChecker.NAME,
                "For each stuck webhook: manually inspect the payload and replay it via "
                + "WebhookProcessingService.process(webhookId). Verify side-effects (ledger entry, "
                + "invoice status) were applied. After successful replay, mark processed=true."),

        Map.entry(SubscriptionInvoicePeriodOverlapChecker.NAME,
                "For each overlapping invoice pair: determine which invoice has the incorrect period. "
                + "Issue a credit note for the overlapping amount, correct the period boundaries, "
                + "and trigger a re-sync of the revenue recognition schedule.")
    );

    /**
     * Returns the repair suggestion for the given invariant name, or a generic fallback.
     */
    public String getSuggestionForInvariant(String invariantName) {
        return SUGGESTIONS.getOrDefault(invariantName,
                "No specific repair suggestion available. Review system logs for the "
                + invariantName + " invariant and consult the integrity playbook.");
    }
}
