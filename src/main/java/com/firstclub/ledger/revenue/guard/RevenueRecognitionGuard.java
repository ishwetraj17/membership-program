package com.firstclub.ledger.revenue.guard;

import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import org.springframework.stereotype.Component;

/**
 * Stateless guard that decides whether a revenue recognition schedule row
 * may be posted, given the <em>current</em> state of its subscription and
 * invoice.
 *
 * <h3>Rule table (applied top-to-bottom; invoice checks take precedence)</h3>
 * <pre>
 * Invoice VOID              → HALT  / REVERSE_ON_VOID   (billing system voided the invoice)
 * Invoice UNCOLLECTIBLE     → FLAG  / DEFER_UNTIL_PAID  (payment unlikely; flag for review)
 *
 * Subscription ACTIVE       → ALLOW / RECOGNIZE          (normal path)
 * Subscription TRIALING     → ALLOW / RECOGNIZE          (trial is a live, billed state)
 * Subscription PAST_DUE     → FLAG  / DEFER_UNTIL_PAID   (recovery might succeed)
 * Subscription PAUSED       → DEFER / SKIP               (voluntarily paused; no charges)
 * Subscription SUSPENDED    → BLOCK / HALT               (involuntary suspension; no revenue)
 * Subscription CANCELLED    → HALT  / HALT               (terminal; no further recognition)
 * Subscription EXPIRED      → HALT  / HALT               (end-of-term; no renewal)
 * Subscription INCOMPLETE   → DEFER / DEFER_UNTIL_PAID   (payment method not confirmed)
 * </pre>
 *
 * <h3>Deferred revenue ceiling</h3>
 * Recognition can never exceed the {@code SUBSCRIPTION_LIABILITY} account balance.
 * That ceiling is enforced separately by
 * {@link com.firstclub.ledger.revenue.service.impl.RevenueRecognitionPostingServiceImpl}.
 */
@Component
public class RevenueRecognitionGuard {

    /**
     * Evaluates whether a schedule row may be posted.
     *
     * @param subscriptionStatus current status of the linked subscription
     * @param invoiceStatus      current status of the linked invoice
     * @return an immutable {@link GuardResult} capturing the decision
     */
    public GuardResult evaluate(SubscriptionStatusV2 subscriptionStatus,
                                InvoiceStatus invoiceStatus) {
        // Invoice-level checks take precedence over subscription status
        if (invoiceStatus == InvoiceStatus.VOID) {
            return GuardResult.of(
                    GuardDecision.HALT,
                    RecognitionPolicyCode.REVERSE_ON_VOID,
                    "Invoice is VOID; recognition halted — any POSTED entries require reversal");
        }
        if (invoiceStatus == InvoiceStatus.UNCOLLECTIBLE) {
            return GuardResult.of(
                    GuardDecision.FLAG,
                    RecognitionPolicyCode.DEFER_UNTIL_PAID,
                    "Invoice is UNCOLLECTIBLE; revenue recognition flagged for operator review");
        }

        // Subscription-level policy
        return switch (subscriptionStatus) {
            case ACTIVE ->
                    GuardResult.of(GuardDecision.ALLOW,
                            RecognitionPolicyCode.RECOGNIZE,
                            "Subscription ACTIVE — recognize normally");
            case TRIALING ->
                    GuardResult.of(GuardDecision.ALLOW,
                            RecognitionPolicyCode.RECOGNIZE,
                            "Subscription TRIALING — recognize normally");
            case PAST_DUE ->
                    GuardResult.of(GuardDecision.FLAG,
                            RecognitionPolicyCode.DEFER_UNTIL_PAID,
                            "Subscription PAST_DUE — revenue flagged; payment recovery pending");
            case PAUSED ->
                    GuardResult.of(GuardDecision.DEFER,
                            RecognitionPolicyCode.SKIP,
                            "Subscription PAUSED — defer recognition until subscription resumes");
            case SUSPENDED ->
                    GuardResult.of(GuardDecision.BLOCK,
                            RecognitionPolicyCode.HALT,
                            "Subscription SUSPENDED — recognition blocked; no charges succeeding");
            case CANCELLED ->
                    GuardResult.of(GuardDecision.HALT,
                            RecognitionPolicyCode.HALT,
                            "Subscription CANCELLED — recognition halted permanently");
            case EXPIRED ->
                    GuardResult.of(GuardDecision.HALT,
                            RecognitionPolicyCode.HALT,
                            "Subscription EXPIRED — recognition halted permanently");
            case INCOMPLETE ->
                    GuardResult.of(GuardDecision.DEFER,
                            RecognitionPolicyCode.DEFER_UNTIL_PAID,
                            "Subscription INCOMPLETE — defer recognition until payment method confirmed");
        };
    }

    /**
     * Returns {@code true} if the decision permits a ledger entry to be written.
     * Only {@link GuardDecision#ALLOW} and {@link GuardDecision#FLAG} allow posting.
     */
    public boolean allowsPosting(GuardDecision decision) {
        return decision == GuardDecision.ALLOW || decision == GuardDecision.FLAG;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    /**
     * Immutable result of a guard evaluation.
     *
     * @param decision   the {@link GuardDecision} for the schedule row
     * @param policyCode the {@link RecognitionPolicyCode} accounting treatment
     * @param reason     human-readable explanation for audit / operator display
     */
    public record GuardResult(
            GuardDecision decision,
            RecognitionPolicyCode policyCode,
            String reason) {

        public static GuardResult of(GuardDecision d, RecognitionPolicyCode p, String r) {
            return new GuardResult(d, p, r);
        }
    }
}
