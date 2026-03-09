package com.firstclub.payments.exception;

import com.firstclub.payments.entity.PaymentIntentStatusV2;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Domain exception for payment intent and attempt errors.
 */
@Getter
public class PaymentIntentException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public PaymentIntentException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // ── Payment intent factory methods ────────────────────────────────────────

    public static PaymentIntentException notFound(Long merchantId, Long id) {
        return new PaymentIntentException(
                "Payment intent " + id + " not found under merchant " + merchantId,
                "PAYMENT_INTENT_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static PaymentIntentException invalidTransition(PaymentIntentStatusV2 from,
                                                            String operation) {
        return new PaymentIntentException(
                "Cannot perform '" + operation + "' on a payment intent in status " + from,
                "INVALID_PAYMENT_INTENT_TRANSITION",
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    public static PaymentIntentException missingPaymentMethod(Long id) {
        return new PaymentIntentException(
                "Payment intent " + id + " has no payment method attached; "
                        + "provide paymentMethodId in the confirm request",
                "MISSING_PAYMENT_METHOD",
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    public static PaymentIntentException amountMismatch(Long id) {
        return new PaymentIntentException(
                "Amount on payment intent " + id + " does not match the associated invoice amount",
                "AMOUNT_MISMATCH",
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    public static PaymentIntentException nonRetriableFailure(Long id) {
        return new PaymentIntentException(
                "Payment intent " + id + " has a non-retriable terminal failure; "
                        + "create a new intent to retry",
                "NON_RETRIABLE_FAILURE",
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    public static PaymentIntentException alreadySucceeded(Long id) {
        return new PaymentIntentException(
                "Payment intent " + id + " has already succeeded",
                "PAYMENT_INTENT_ALREADY_SUCCEEDED",
                HttpStatus.CONFLICT
        );
    }

    // ── Attempt factory methods ───────────────────────────────────────────────

    public static PaymentIntentException attemptImmutable(Long attemptId) {
        return new PaymentIntentException(
                "Payment attempt " + attemptId + " is in a terminal state and cannot be modified",
                "ATTEMPT_IMMUTABLE",
                HttpStatus.CONFLICT
        );
    }

    public static PaymentIntentException attemptNotFound(Long attemptId, Long intentId) {
        return new PaymentIntentException(
                "Attempt " + attemptId + " not found for payment intent " + intentId,
                "ATTEMPT_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }
}
