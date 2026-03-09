package com.firstclub.customer.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Domain exception for customer-related errors with HTTP status mapping.
 *
 * Mirrors {@link com.firstclub.merchant.exception.MerchantException}:
 * carries an {@code errorCode} and an {@link HttpStatus} alongside the message.
 */
@Getter
public class CustomerException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public CustomerException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // ── Factory methods ──────────────────────────────────────────────────────

    public static CustomerException customerNotFound(Long merchantId, Long customerId) {
        return new CustomerException(
                "Customer " + customerId + " not found in merchant " + merchantId,
                "CUSTOMER_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static CustomerException duplicateEmail(Long merchantId, String email) {
        return new CustomerException(
                "Email '" + email + "' is already registered for merchant " + merchantId,
                "DUPLICATE_CUSTOMER_EMAIL",
                HttpStatus.CONFLICT
        );
    }

    public static CustomerException duplicateExternalId(Long merchantId, String externalId) {
        return new CustomerException(
                "External customer ID '" + externalId + "' is already in use for merchant " + merchantId,
                "DUPLICATE_EXTERNAL_CUSTOMER_ID",
                HttpStatus.CONFLICT
        );
    }

    public static CustomerException customerNotActive(Long customerId) {
        return new CustomerException(
                "Customer " + customerId + " is not ACTIVE and cannot be used for new subscriptions/payments",
                "CUSTOMER_NOT_ACTIVE",
                HttpStatus.BAD_REQUEST
        );
    }

    public static CustomerException invalidStatusTransition(String from, String to) {
        return new CustomerException(
                "Invalid customer status transition: " + from + " → " + to,
                "INVALID_CUSTOMER_STATUS_TRANSITION",
                HttpStatus.BAD_REQUEST
        );
    }

    public static CustomerException authorNotFound(Long userId) {
        return new CustomerException(
                "Author user " + userId + " not found",
                "NOTE_AUTHOR_NOT_FOUND",
                HttpStatus.BAD_REQUEST
        );
    }
}
