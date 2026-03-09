package com.firstclub.subscription.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * Domain exception for subscription-related errors.
 *
 * <p>Follows the same factory-method pattern as
 * {@link com.firstclub.catalog.exception.CatalogException}.
 */
@Getter
public class SubscriptionException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public SubscriptionException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // ── Subscription factory methods ─────────────────────────────────────────

    public static SubscriptionException notFound(Long merchantId, Long subscriptionId) {
        return new SubscriptionException(
                "Subscription " + subscriptionId + " not found for merchant " + merchantId,
                "SUBSCRIPTION_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static SubscriptionException duplicateActiveSubscription(Long customerId, Long productId) {
        return new SubscriptionException(
                "Customer " + customerId + " already has an active/trialing subscription for product " + productId,
                "DUPLICATE_ACTIVE_SUBSCRIPTION",
                HttpStatus.CONFLICT
        );
    }

    public static SubscriptionException invalidStateTransition(
            com.firstclub.subscription.entity.SubscriptionStatusV2 from,
            com.firstclub.subscription.entity.SubscriptionStatusV2 to) {
        return new SubscriptionException(
                "Cannot transition subscription from " + from + " to " + to,
                "INVALID_STATE_TRANSITION",
                HttpStatus.BAD_REQUEST
        );
    }

    public static SubscriptionException alreadyCancelled(Long subscriptionId) {
        return new SubscriptionException(
                "Subscription " + subscriptionId + " is already cancelled",
                "SUBSCRIPTION_ALREADY_CANCELLED",
                HttpStatus.BAD_REQUEST
        );
    }

    public static SubscriptionException alreadyPaused(Long subscriptionId) {
        return new SubscriptionException(
                "Subscription " + subscriptionId + " is already paused",
                "SUBSCRIPTION_ALREADY_PAUSED",
                HttpStatus.BAD_REQUEST
        );
    }

    public static SubscriptionException notPaused(Long subscriptionId) {
        return new SubscriptionException(
                "Subscription " + subscriptionId + " is not paused — cannot resume",
                "SUBSCRIPTION_NOT_PAUSED",
                HttpStatus.BAD_REQUEST
        );
    }

    public static SubscriptionException terminalSubscription(Long subscriptionId) {
        return new SubscriptionException(
                "Subscription " + subscriptionId + " is in a terminal state; no further actions allowed",
                "SUBSCRIPTION_TERMINAL",
                HttpStatus.BAD_REQUEST
        );
    }

    public static SubscriptionException noPriceVersionAvailable(Long priceId) {
        return new SubscriptionException(
                "No effective price version found for price " + priceId,
                "NO_PRICE_VERSION_AVAILABLE",
                HttpStatus.BAD_REQUEST
        );
    }

    // ── Schedule factory methods ──────────────────────────────────────────────

    public static SubscriptionException scheduleNotFound(Long scheduleId) {
        return new SubscriptionException(
                "Schedule " + scheduleId + " not found",
                "SCHEDULE_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static SubscriptionException scheduleEffectiveAtInPast() {
        return new SubscriptionException(
                "Scheduled effectiveAt must be in the future",
                "SCHEDULE_EFFECTIVE_AT_IN_PAST",
                HttpStatus.BAD_REQUEST
        );
    }

    public static SubscriptionException duplicateScheduleConflict(LocalDateTime at) {
        return new SubscriptionException(
                "A SCHEDULED action already exists at " + at + " for this subscription",
                "DUPLICATE_SCHEDULE_CONFLICT",
                HttpStatus.CONFLICT
        );
    }

    public static SubscriptionException scheduleNotCancellable(Long scheduleId) {
        return new SubscriptionException(
                "Schedule " + scheduleId + " is not in a SCHEDULED state and cannot be cancelled",
                "SCHEDULE_NOT_CANCELLABLE",
                HttpStatus.BAD_REQUEST
        );
    }
}
