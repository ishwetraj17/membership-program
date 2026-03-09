package com.firstclub.platform.statemachine;

import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.model.PaymentIntentStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generic state-machine validator.
 *
 * <p>Maintains a registry of allowed status transitions per entity type and
 * throws {@link MembershipException} (BAD_REQUEST / INVALID_STATUS_TRANSITION)
 * whenever a caller attempts an illegal move.
 *
 * <p>Entity keys are plain strings ("SUBSCRIPTION", "INVOICE", "PAYMENT_INTENT")
 * so the registry can hold rules for heterogeneous enum hierarchies without
 * wrestling with Java's generic invariance.
 *
 * <p>Usage:
 * <pre>
 *   stateMachineValidator.validate("SUBSCRIPTION", oldStatus, newStatus);
 * </pre>
 */
@Component
public class StateMachineValidator {

    // entity → (fromStatus.name() → Set of allowed toStatus.name())
    private final Map<String, Map<String, Set<String>>> rules = new HashMap<>();

    public StateMachineValidator() {
        registerSubscriptionRules();
        registerInvoiceRules();
        registerPaymentIntentRules();
        registerMerchantRules();
        registerCustomerRules();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Validates that the {@code from → to} transition is permitted for
     * {@code entity}.  Throws {@link MembershipException} with error code
     * {@code INVALID_STATUS_TRANSITION} and HTTP 400 if the transition is not
     * in the allowed set.
     *
     * @param entity identifier for the state machine (e.g. "SUBSCRIPTION")
     * @param from   current status enum constant
     * @param to     desired target status enum constant
     * @throws MembershipException if the transition is illegal
     */
    public void validate(String entity, Enum<?> from, Enum<?> to) {
        Map<String, Set<String>> entityRules = rules.get(entity);
        if (entityRules == null) {
            throw new MembershipException(
                "Unknown entity type for state machine: " + entity,
                "INVALID_STATE_MACHINE_ENTITY"
            );
        }

        Set<String> allowed = entityRules.getOrDefault(from.name(), Collections.emptySet());
        if (!allowed.contains(to.name())) {
            throw new MembershipException(
                "Invalid status transition for " + entity + ": " + from.name() + " -> " + to.name(),
                "INVALID_STATUS_TRANSITION"
            );
        }
    }

    // -------------------------------------------------------------------------
    // Rule registration
    // -------------------------------------------------------------------------

    private void registerSubscriptionRules() {
        // PENDING → ACTIVE | CANCELLED
        addTransitions("SUBSCRIPTION", SubscriptionStatus.PENDING,
                SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED);

        // ACTIVE → PAST_DUE | CANCELLED | SUSPENDED | EXPIRED
        addTransitions("SUBSCRIPTION", SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PAST_DUE, SubscriptionStatus.CANCELLED,
                SubscriptionStatus.SUSPENDED, SubscriptionStatus.EXPIRED);

        // PAST_DUE → ACTIVE | CANCELLED | SUSPENDED
        addTransitions("SUBSCRIPTION", SubscriptionStatus.PAST_DUE,
                SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED,
                SubscriptionStatus.SUSPENDED);

        // SUSPENDED → ACTIVE | CANCELLED
        addTransitions("SUBSCRIPTION", SubscriptionStatus.SUSPENDED,
                SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED);

        // EXPIRED → ACTIVE (renewal only)
        addTransitions("SUBSCRIPTION", SubscriptionStatus.EXPIRED,
                SubscriptionStatus.ACTIVE);

        // CANCELLED → (terminal)
        addTransitions("SUBSCRIPTION", SubscriptionStatus.CANCELLED);
    }

    private void registerInvoiceRules() {
        // DRAFT → OPEN | VOID
        addTransitions("INVOICE", InvoiceStatus.DRAFT,
                InvoiceStatus.OPEN, InvoiceStatus.VOID);

        // OPEN → PAID | VOID | UNCOLLECTIBLE
        addTransitions("INVOICE", InvoiceStatus.OPEN,
                InvoiceStatus.PAID, InvoiceStatus.VOID, InvoiceStatus.UNCOLLECTIBLE);

        // PAID, VOID, UNCOLLECTIBLE → (terminal)
        addTransitions("INVOICE", InvoiceStatus.PAID);
        addTransitions("INVOICE", InvoiceStatus.VOID);
        addTransitions("INVOICE", InvoiceStatus.UNCOLLECTIBLE);
    }

    private void registerPaymentIntentRules() {
        // REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION | FAILED
        addTransitions("PAYMENT_INTENT", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD,
                PaymentIntentStatus.REQUIRES_CONFIRMATION, PaymentIntentStatus.FAILED);

        // REQUIRES_CONFIRMATION → PROCESSING | REQUIRES_ACTION | FAILED
        addTransitions("PAYMENT_INTENT", PaymentIntentStatus.REQUIRES_CONFIRMATION,
                PaymentIntentStatus.PROCESSING, PaymentIntentStatus.REQUIRES_ACTION, PaymentIntentStatus.FAILED);

        // REQUIRES_ACTION → PROCESSING | FAILED
        addTransitions("PAYMENT_INTENT", PaymentIntentStatus.REQUIRES_ACTION,
                PaymentIntentStatus.PROCESSING, PaymentIntentStatus.FAILED);

        // PROCESSING → SUCCEEDED | REQUIRES_ACTION | FAILED
        addTransitions("PAYMENT_INTENT", PaymentIntentStatus.PROCESSING,
                PaymentIntentStatus.SUCCEEDED, PaymentIntentStatus.REQUIRES_ACTION, PaymentIntentStatus.FAILED);

        // SUCCEEDED → (terminal)
        addTransitions("PAYMENT_INTENT", PaymentIntentStatus.SUCCEEDED);

        // FAILED → REQUIRES_PAYMENT_METHOD (retry path)
        addTransitions("PAYMENT_INTENT", PaymentIntentStatus.FAILED,
                PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
    }

    private void registerMerchantRules() {
        // PENDING → ACTIVE | CLOSED
        addTransitions("MERCHANT", MerchantStatus.PENDING,
                MerchantStatus.ACTIVE, MerchantStatus.CLOSED);

        // ACTIVE → SUSPENDED | CLOSED
        addTransitions("MERCHANT", MerchantStatus.ACTIVE,
                MerchantStatus.SUSPENDED, MerchantStatus.CLOSED);

        // SUSPENDED → ACTIVE | CLOSED
        addTransitions("MERCHANT", MerchantStatus.SUSPENDED,
                MerchantStatus.ACTIVE, MerchantStatus.CLOSED);

        // CLOSED → (terminal)
        addTransitions("MERCHANT", MerchantStatus.CLOSED);
    }

    private void registerCustomerRules() {
        // ACTIVE → INACTIVE | BLOCKED
        addTransitions("CUSTOMER", CustomerStatus.ACTIVE,
                CustomerStatus.INACTIVE, CustomerStatus.BLOCKED);

        // INACTIVE → ACTIVE | BLOCKED
        addTransitions("CUSTOMER", CustomerStatus.INACTIVE,
                CustomerStatus.ACTIVE, CustomerStatus.BLOCKED);

        // BLOCKED → ACTIVE (re-activation allowed by admin)
        addTransitions("CUSTOMER", CustomerStatus.BLOCKED,
                CustomerStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    @SafeVarargs
    private <E extends Enum<E>> void addTransitions(String entity, E from, E... targets) {
        Set<String> targetNames = new HashSet<>();
        for (E t : targets) {
            targetNames.add(t.name());
        }
        rules.computeIfAbsent(entity, k -> new HashMap<>())
             .put(from.name(), Collections.unmodifiableSet(targetNames));
    }
}
