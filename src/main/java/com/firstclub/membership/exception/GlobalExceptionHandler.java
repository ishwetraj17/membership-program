package com.firstclub.membership.exception;

import jakarta.persistence.OptimisticLockException;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MembershipException.class)
    public ResponseEntity<ErrorResponse> handleMembershipException(MembershipException e) {
        log.error("Membership error [{}]: {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(error(e.getMessage(), e.getErrorCode(), e.getHttpStatus().value(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(err ->
                fieldErrors.put(((FieldError) err).getField(), err.getDefaultMessage()));

        return ResponseEntity.badRequest()
                .body(error("Validation failed", "VALIDATION_ERROR", 400, fieldErrors));
    }

    /**
     * Catches unique-constraint violations (e.g. duplicate active subscription).
     * The partial unique index on subscriptions(user_id) WHERE status = 'ACTIVE'
     * is the DB-level safety net; this handler converts it into a 409 response.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        log.error("Data integrity violation: {}", e.getMostSpecificCause().getMessage());

        String message = "Data constraint violated";
        if (e.getMessage() != null && e.getMessage().contains("uq_user_active_subscription")) {
            message = "User already has an active subscription";
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(message, "DATA_INTEGRITY_VIOLATION", 409, null));
    }

    /**
     * Catches optimistic-lock conflicts — two concurrent requests updated the same
     * subscription row simultaneously. The client should retry.
     *
     * Two handlers cover both exception paths:
     *  - Spring Data JPA translates Hibernate's StaleObjectStateException →
     *    JpaOptimisticLockingFailureException (extends OptimisticLockingFailureException).
     *  - Direct JPA EntityManager usage throws jakarta.persistence.OptimisticLockException.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleSpringOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("Concurrent modification detected — please retry", "CONCURRENT_MODIFICATION", 409, null));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleJpaOptimisticLock(OptimisticLockException e) {
        log.warn("Optimistic lock conflict (JPA): {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("Concurrent modification detected — please retry", "CONCURRENT_MODIFICATION", 409, null));
    }

    /**
     * Catches invalid enum values in path variables (e.g. /plans/type/INVALID).
     * Spring throws MethodArgumentTypeMismatchException when path conversion fails.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = String.format("Invalid value '%s' for parameter '%s'", e.getValue(), e.getName());
        Class<?> requiredType = e.getRequiredType();
        if (requiredType != null && requiredType.isEnum()) {
            message += ". Valid values: " + java.util.Arrays.toString(requiredType.getEnumConstants());
        }
        return ResponseEntity.badRequest()
                .body(error(message, "INVALID_PARAMETER_VALUE", 400, null));
    }

    /**
     * Catches malformed JSON request bodies.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(error("Malformed or unreadable request body", "MALFORMED_REQUEST", 400, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(error("An unexpected error occurred", "INTERNAL_ERROR", 500, null));
    }

    // ─── Response model ───────────────────────────────────────────────────────

    private static ErrorResponse error(String message, String code, int status,
                                       Map<String, String> validationErrors) {
        return ErrorResponse.builder()
                .message(message)
                .errorCode(code)
                .httpStatus(status)
                .timestamp(LocalDateTime.now())
                .validationErrors(validationErrors)
                .build();
    }

    @Data
    @Builder
    public static class ErrorResponse {
        private String message;
        private String errorCode;
        private Integer httpStatus;
        private LocalDateTime timestamp;
        private Map<String, String> validationErrors;
    }
}
