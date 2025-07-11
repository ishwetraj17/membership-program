package com.firstclub.membership.exception;

import lombok.Data;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the application
 * 
 * Catches all exceptions and returns proper HTTP responses.
 * Provides consistent error format across all APIs.
 * 
 * Implemented by Shwet Raj
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle membership-specific business logic errors
     */
    @ExceptionHandler(MembershipException.class)
    public ResponseEntity<ErrorResponse> handleMembershipException(MembershipException e) {
        log.error("Membership error: {}", e.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .build();
            
        // TODO: Map error codes to appropriate HTTP status codes
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle validation errors from @Valid annotations
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException e) {
        log.error("Validation error: {}", e.getMessage());
        
        Map<String, String> validationErrors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            validationErrors.put(fieldName, errorMessage);
        });

        ErrorResponse error = ErrorResponse.builder()
            .message("Validation failed")
            .errorCode("VALIDATION_ERROR")
            .timestamp(LocalDateTime.now())
            .validationErrors(validationErrors)
            .build();
            
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle unexpected errors
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        log.error("Unexpected error occurred", e);
        
        ErrorResponse error = ErrorResponse.builder()
            .message("An unexpected error occurred")
            .errorCode("INTERNAL_ERROR")
            .timestamp(LocalDateTime.now())
            .build();
            
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Standard error response format
     */
    @Data
    @Builder
    public static class ErrorResponse {
        private String message;
        private String errorCode;
        private LocalDateTime timestamp;
        private Map<String, String> validationErrors;
    }
}