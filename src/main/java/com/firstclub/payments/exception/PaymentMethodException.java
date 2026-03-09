package com.firstclub.payments.exception;

import com.firstclub.payments.entity.PaymentMethodType;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Domain exception for payment-method and mandate errors.
 *
 * <p>Follows the factory-method pattern established by
 * {@link com.firstclub.subscription.exception.SubscriptionException}.
 */
@Getter
public class PaymentMethodException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public PaymentMethodException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // ── Payment method factory methods ────────────────────────────────────────

    public static PaymentMethodException notFound(Long merchantId, Long customerId, Long id) {
        return new PaymentMethodException(
                "Payment method " + id + " not found for customer " + customerId
                        + " under merchant " + merchantId,
                "PAYMENT_METHOD_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static PaymentMethodException duplicateProviderToken(String provider, String token) {
        return new PaymentMethodException(
                "Provider token already registered with provider '" + provider + "'",
                "DUPLICATE_PROVIDER_TOKEN",
                HttpStatus.CONFLICT
        );
    }

    public static PaymentMethodException notUsable(Long id) {
        return new PaymentMethodException(
                "Payment method " + id + " is not in ACTIVE status and cannot be used",
                "PAYMENT_METHOD_NOT_USABLE",
                HttpStatus.BAD_REQUEST
        );
    }

    public static PaymentMethodException alreadyRevoked(Long id) {
        return new PaymentMethodException(
                "Payment method " + id + " is already revoked",
                "PAYMENT_METHOD_ALREADY_REVOKED",
                HttpStatus.BAD_REQUEST
        );
    }

    public static PaymentMethodException unsupportedMandateType(PaymentMethodType type) {
        return new PaymentMethodException(
                "Payment method type " + type + " does not support mandate creation",
                "UNSUPPORTED_MANDATE_METHOD_TYPE",
                HttpStatus.BAD_REQUEST
        );
    }

    // ── Mandate factory methods ───────────────────────────────────────────────

    public static PaymentMethodException mandateNotFound(Long mandateId, Long paymentMethodId) {
        return new PaymentMethodException(
                "Mandate " + mandateId + " not found for payment method " + paymentMethodId,
                "MANDATE_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static PaymentMethodException mandateAlreadyRevoked(Long mandateId) {
        return new PaymentMethodException(
                "Mandate " + mandateId + " is already revoked",
                "MANDATE_ALREADY_REVOKED",
                HttpStatus.BAD_REQUEST
        );
    }
}
