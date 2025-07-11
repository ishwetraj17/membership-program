package com.firstclub.membership.exception;

import lombok.Getter;

/**
 * Custom exception for membership-related errors
 * 
 * Provides error codes for better error handling and debugging.
 * Used throughout the service layer for business logic violations.
 * 
 * Implemented by Shwet Raj
 */
@Getter
public class MembershipException extends RuntimeException {

    private final String errorCode;

    public MembershipException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public MembershipException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}