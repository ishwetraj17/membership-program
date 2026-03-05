package com.firstclub.membership.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.Data;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for the application
 *
 * Catches all exceptions and returns consistent JSON error responses.
 * Every new exception type gets its own handler — never rely solely on the
 * generic catch-all, which hides useful diagnostic information.
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
        log.error("Membership error [{}]: {}", e.getErrorCode(), e.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .build();
            
        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle validation errors from @Valid on @RequestBody
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException e) {
        log.warn("Validation error: {}", e.getMessage());
        
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
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .validationErrors(validationErrors)
            .build();
            
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle @Validated constraint violations on path/query parameters.
     * Example: GET /users/-1 when @Positive is placed on the id param.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("Constraint violation: {}", e.getMessage());

        Map<String, String> validationErrors = e.getConstraintViolations().stream()
            .collect(Collectors.toMap(
                v -> {
                    String path = v.getPropertyPath().toString();
                    // Strip method name prefix from path (e.g. "getUserById.id" -> "id")
                    int dot = path.lastIndexOf('.');
                    return dot >= 0 ? path.substring(dot + 1) : path;
                },
                v -> v.getMessage(),
                (existing, replacement) -> existing
            ));

        ErrorResponse error = ErrorResponse.builder()
            .message("Invalid request parameters")
            .errorCode("CONSTRAINT_VIOLATION")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .validationErrors(validationErrors)
            .build();

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle malformed or unreadable JSON in the request body.
     * Without this, Spring returns a non-standard 400 with an HTML error page.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException e) {
        log.warn("Malformed JSON request: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message("Malformed JSON request body")
            .errorCode("INVALID_JSON")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .build();

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle requests to routes that don't exist (404).
     * Without this, Spring returns its default white-label error page.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("No resource found: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message("The requested endpoint does not exist")
            .errorCode("ENDPOINT_NOT_FOUND")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.NOT_FOUND.value())
            .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle wrong HTTP method on a valid endpoint (405 Method Not Allowed).
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {} — supported: {}", e.getMethod(), e.getSupportedMethods());

        String supported = e.getSupportedHttpMethods() != null
            ? e.getSupportedHttpMethods().stream().map(Object::toString).collect(Collectors.joining(", "))
            : "unknown";

        ErrorResponse error = ErrorResponse.builder()
            .message(String.format("HTTP method '%s' is not allowed. Supported: %s", e.getMethod(), supported))
            .errorCode("METHOD_NOT_ALLOWED")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.METHOD_NOT_ALLOWED.value())
            .build();

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    /**
     * Handle type mismatch on path/query variables.
     * Example: GET /users/abc when id is a Long.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type mismatch for parameter '{}': value='{}', expected type={}",
            e.getName(), e.getValue(), e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown");

        String expectedType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown";
        ErrorResponse error = ErrorResponse.builder()
            .message(String.format("Invalid value '%s' for parameter '%s'. Expected type: %s",
                e.getValue(), e.getName(), expectedType))
            .errorCode("INVALID_PARAMETER_TYPE")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .build();

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Catch-all for unexpected errors.
     * Stack trace is logged server-side but NOT returned to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        log.error("Unexpected error occurred", e);
        
        ErrorResponse error = ErrorResponse.builder()
            .message("An unexpected error occurred")
            .errorCode("INTERNAL_ERROR")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .build();
            
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Consistent error response format for all handled exceptions.
     */
    @Data
    @Builder
    public static class ErrorResponse {
        private String message;
        private String errorCode;
        private LocalDateTime timestamp;
        private Integer httpStatus;
        private Map<String, String> validationErrors;
    }
}