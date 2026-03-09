package com.firstclub.merchant.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Custom exception for merchant/tenant-related errors with HTTP status mapping.
 *
 * Mirrors {@link com.firstclub.membership.exception.MembershipException}:
 * errorCode (String) + httpStatus (HttpStatus) + factory methods.
 *
 * Implemented by Shwet Raj
 */
@Getter
public class MerchantException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public MerchantException(String message, String errorCode) {
        this(message, errorCode, HttpStatus.BAD_REQUEST);
    }

    public MerchantException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public MerchantException(String message, String errorCode, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static MerchantException merchantNotFound(Long id) {
        return new MerchantException(
                "Merchant with ID " + id + " not found",
                "MERCHANT_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static MerchantException merchantCodeTaken(String code) {
        return new MerchantException(
                "Merchant code '" + code + "' is already in use",
                "MERCHANT_CODE_TAKEN",
                HttpStatus.CONFLICT
        );
    }

    public static MerchantException merchantNotActive(Long id) {
        return new MerchantException(
                "Merchant with ID " + id + " is not in ACTIVE status",
                "MERCHANT_NOT_ACTIVE",
                HttpStatus.BAD_REQUEST
        );
    }

    public static MerchantException duplicateUserAssignment(Long merchantId, Long userId) {
        return new MerchantException(
                "User " + userId + " is already assigned to merchant " + merchantId,
                "DUPLICATE_MERCHANT_USER",
                HttpStatus.CONFLICT
        );
    }

    public static MerchantException lastOwnerRemoval() {
        return new MerchantException(
                "Cannot remove the last OWNER from a merchant",
                "CANNOT_REMOVE_LAST_OWNER",
                HttpStatus.BAD_REQUEST
        );
    }

    public static MerchantException userNotFound(Long userId) {
        return new MerchantException(
                "User with ID " + userId + " not found",
                "USER_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static MerchantException invalidStatusTransition(String from, String to) {
        return new MerchantException(
                "Invalid merchant status transition: " + from + " -> " + to,
                "INVALID_STATUS_TRANSITION",
                HttpStatus.BAD_REQUEST
        );
    }

    public static MerchantException userNotInMerchant(Long merchantId, Long userId) {
        return new MerchantException(
                "User " + userId + " is not assigned to merchant " + merchantId,
                "USER_NOT_IN_MERCHANT",
                HttpStatus.NOT_FOUND
        );
    }
}
