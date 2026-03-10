package com.firstclub.dunning.classification;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Translates raw payment-gateway failure codes into {@link FailureCategory} values.
 *
 * <p>The internal map is keyed on lower-cased, underscore-normalised codes so that
 * Stripe-style strings ({@code "insufficient_funds"}), camelCase variants, and
 * simple gateway-level labels all resolve correctly.  Any code not present in the
 * map is returned as {@link FailureCategory#UNKNOWN}.
 *
 * <p>This component is intentionally stateless: add new mappings to {@code CODE_MAP}
 * as new gateways are integrated.
 */
@Component
public class FailureCodeClassifier {

    private static final Map<String, FailureCategory> CODE_MAP = Map.ofEntries(

        // ── Insufficient funds ────────────────────────────────────────────────
        Map.entry("insufficient_funds",           FailureCategory.INSUFFICIENT_FUNDS),
        Map.entry("withdrawal_count_limit_exceeded", FailureCategory.INSUFFICIENT_FUNDS),

        // ── Generic decline ───────────────────────────────────────────────────
        Map.entry("card_declined",                FailureCategory.CARD_DECLINED_GENERIC),
        Map.entry("generic_decline",              FailureCategory.CARD_DECLINED_GENERIC),
        Map.entry("do_not_try_again",             FailureCategory.CARD_DECLINED_GENERIC),
        Map.entry("restricted_card",              FailureCategory.CARD_DECLINED_GENERIC),
        Map.entry("gateway_declined",             FailureCategory.CARD_DECLINED_GENERIC),
        Map.entry("simulated_decline",            FailureCategory.CARD_DECLINED_GENERIC),

        // ── Gateway timeout / processing error ────────────────────────────────
        Map.entry("gateway_timeout",              FailureCategory.GATEWAY_TIMEOUT),
        Map.entry("processing_error",             FailureCategory.GATEWAY_TIMEOUT),
        Map.entry("reenter_transaction",          FailureCategory.GATEWAY_TIMEOUT),

        // ── Card expired ──────────────────────────────────────────────────────
        Map.entry("expired_card",                 FailureCategory.CARD_EXPIRED),
        Map.entry("invalid_expiry_month",         FailureCategory.CARD_EXPIRED),
        Map.entry("invalid_expiry_year",          FailureCategory.CARD_EXPIRED),

        // ── Card not supported ────────────────────────────────────────────────
        Map.entry("card_not_supported",           FailureCategory.CARD_NOT_SUPPORTED),
        Map.entry("currency_not_supported",       FailureCategory.CARD_NOT_SUPPORTED),
        Map.entry("transaction_not_allowed",      FailureCategory.CARD_NOT_SUPPORTED),

        // ── Issuer not available ──────────────────────────────────────────────
        Map.entry("issuer_not_available",         FailureCategory.ISSUER_NOT_AVAILABLE),
        Map.entry("card_issuer_contact_bank",     FailureCategory.ISSUER_NOT_AVAILABLE),
        Map.entry("call_issuer",                  FailureCategory.ISSUER_NOT_AVAILABLE),

        // ── Card stolen ───────────────────────────────────────────────────────
        Map.entry("stolen_card",                  FailureCategory.CARD_STOLEN),

        // ── Card lost ─────────────────────────────────────────────────────────
        Map.entry("lost_card",                    FailureCategory.CARD_LOST),

        // ── Fraudulent ────────────────────────────────────────────────────────
        Map.entry("fraudulent",                   FailureCategory.FRAUDULENT),

        // ── Do not honour ─────────────────────────────────────────────────────
        Map.entry("do_not_honor",                 FailureCategory.DO_NOT_HONOR),
        Map.entry("no_action_taken",              FailureCategory.DO_NOT_HONOR),

        // ── Invalid account ───────────────────────────────────────────────────
        Map.entry("invalid_account",              FailureCategory.INVALID_ACCOUNT),
        Map.entry("account_blacklisted",          FailureCategory.INVALID_ACCOUNT)
    );

    /**
     * Classify {@code failureCode} into a {@link FailureCategory}.
     *
     * @param failureCode raw code from the payment gateway (may be {@code null})
     * @return classified category; never {@code null}
     */
    public FailureCategory classify(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return FailureCategory.UNKNOWN;
        }
        // Normalise: lower-case and replace hyphens with underscores
        String normalised = failureCode.trim().toLowerCase().replace('-', '_');
        FailureCategory result = CODE_MAP.get(normalised);
        return result != null ? result : FailureCategory.UNKNOWN;
    }
}
