package com.firstclub.support.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Domain exception for support-case-related errors with HTTP status mapping.
 */
@Getter
public class SupportCaseException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public SupportCaseException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // ── Factory methods ──────────────────────────────────────────────────────

    public static SupportCaseException caseNotFound(Long caseId) {
        return new SupportCaseException(
                "Support case " + caseId + " not found",
                "SUPPORT_CASE_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static SupportCaseException linkedEntityNotFound(String entityType, Long entityId) {
        return new SupportCaseException(
                "Linked entity " + entityType + " with id " + entityId + " does not exist",
                "SUPPORT_CASE_LINKED_ENTITY_NOT_FOUND",
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    public static SupportCaseException unknownLinkedEntityType(String entityType) {
        return new SupportCaseException(
                "Unknown linked entity type: " + entityType
                        + ". Valid types: CUSTOMER, SUBSCRIPTION, INVOICE, PAYMENT_INTENT, REFUND, DISPUTE, RECON_MISMATCH",
                "SUPPORT_CASE_UNKNOWN_ENTITY_TYPE",
                HttpStatus.BAD_REQUEST
        );
    }

    public static SupportCaseException caseAlreadyClosed(Long caseId) {
        return new SupportCaseException(
                "Support case " + caseId + " is already CLOSED and cannot be modified",
                "SUPPORT_CASE_ALREADY_CLOSED",
                HttpStatus.CONFLICT
        );
    }
}
