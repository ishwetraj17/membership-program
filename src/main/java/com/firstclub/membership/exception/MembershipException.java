package com.firstclub.membership.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Custom exception for membership-related errors with HTTP status mapping
 * 
 * Enhanced exception handling with:
 * - Error codes for client identification
 * - HTTP status mapping for proper REST responses
 * - Detailed error messages for debugging
 * - Predefined common error types
 * 
 * Implemented by Shwet Raj
 */
@Getter
public class MembershipException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public MembershipException(String message, String errorCode) {
        this(message, errorCode, HttpStatus.BAD_REQUEST);
    }

    public MembershipException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public MembershipException(String message, String errorCode, Throwable cause) {
        this(message, errorCode, HttpStatus.BAD_REQUEST, cause);
    }

    public MembershipException(String message, String errorCode, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
    
    // Predefined exception factory methods for common scenarios
    
    public static MembershipException userNotFound(Long userId) {
        return new MembershipException(
            "User with ID " + userId + " not found", 
            "USER_NOT_FOUND", 
            HttpStatus.NOT_FOUND
        );
    }
    
    public static MembershipException planNotFound(Long planId) {
        return new MembershipException(
            "Membership plan with ID " + planId + " not found", 
            "PLAN_NOT_FOUND", 
            HttpStatus.NOT_FOUND
        );
    }
    
    public static MembershipException subscriptionNotFound(Long subscriptionId) {
        return new MembershipException(
            "Subscription with ID " + subscriptionId + " not found", 
            "SUBSCRIPTION_NOT_FOUND", 
            HttpStatus.NOT_FOUND
        );
    }
    
    public static MembershipException tierNotFound(String tierName) {
        return new MembershipException(
            "Membership tier '" + tierName + "' not found", 
            "TIER_NOT_FOUND", 
            HttpStatus.NOT_FOUND
        );
    }
    
    public static MembershipException userAlreadySubscribed(Long userId) {
        return new MembershipException(
            "User " + userId + " already has an active subscription", 
            "USER_ALREADY_SUBSCRIBED", 
            HttpStatus.CONFLICT
        );
    }
    
    public static MembershipException invalidSubscriptionStatus(String operation) {
        return new MembershipException(
            "Cannot perform " + operation + " on subscription with current status", 
            "INVALID_SUBSCRIPTION_STATUS", 
            HttpStatus.BAD_REQUEST
        );
    }
    
    public static MembershipException invalidPlanTransition(String from, String to) {
        return new MembershipException(
            "Invalid plan transition from " + from + " to " + to, 
            "INVALID_PLAN_TRANSITION", 
            HttpStatus.BAD_REQUEST
        );
    }
    
    public static MembershipException subscriptionNotOwnedByUser(Long subscriptionId, Long userId) {
        return new MembershipException(
            "Subscription " + subscriptionId + " does not belong to user " + userId, 
            "SUBSCRIPTION_NOT_OWNED", 
            HttpStatus.FORBIDDEN
        );
    }
    
    public static MembershipException inactivePlan(Long planId) {
        return new MembershipException(
            "Cannot subscribe to inactive plan " + planId, 
            "INACTIVE_PLAN", 
            HttpStatus.BAD_REQUEST
        );
    }
}