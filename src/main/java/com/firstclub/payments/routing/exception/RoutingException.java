package com.firstclub.payments.routing.exception;

import org.springframework.http.HttpStatus;

public class RoutingException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public RoutingException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() { return errorCode; }
    public HttpStatus getHttpStatus() { return httpStatus; }

    public static RoutingException noEligibleGateway(String methodType, String currency) {
        return new RoutingException(
                "No eligible gateway found for method=" + methodType + " currency=" + currency
                        + ". All configured gateways are DOWN or no active rules exist.",
                "NO_ELIGIBLE_GATEWAY",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    public static RoutingException ruleNotFound(Long id) {
        return new RoutingException(
                "Gateway route rule with id=" + id + " not found.",
                "ROUTE_RULE_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }
}
