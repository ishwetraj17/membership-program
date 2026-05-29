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
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ErrorResponse> handleValidation(BindException e) {
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
     * Catches missing required request parameters (e.g. /plans/compare without ?planIds=).
     * Without this handler the catch-all returns 500; this returns the correct 400.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        String message = String.format("Required parameter '%s' is missing", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(error(message, "MISSING_REQUIRED_PARAMETER", 400, null));
    }

    /**
     * Catches invalid sort field names (e.g. ?sort=nonExistentField,desc).
     * Spring Data throws PropertyReferenceException when the field doesn't exist on the entity.
     * Without this handler the catch-all returns 500; this returns the correct 400.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(PropertyReferenceException e) {
        String message = String.format("Invalid sort field '%s'", e.getPropertyName());
        return ResponseEntity.badRequest()
                .body(error(message, "INVALID_SORT_FIELD", 400, null));
    }

    /**
     * Catches malformed JSON request bodies.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(error("Malformed or unreadable request body", "MALFORMED_REQUEST", 400, null));
    }

    /**
     * Catches unsupported Content-Type headers (e.g. text/plain on a JSON endpoint).
     * Without this handler the catch-all returns 500; this returns the correct 415.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        String message = String.format("Content-Type '%s' is not supported. Use 'application/json'",
                e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error(message, "UNSUPPORTED_MEDIA_TYPE", 415, null));
    }

    /**
     * Catches unsupported HTTP method errors (e.g. POST on a PUT-only endpoint).
     * Without this handler the catch-all returns 500; this returns the correct 405.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String message = String.format("HTTP method '%s' is not supported for this endpoint. Allowed: %s",
                e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(error(message, "METHOD_NOT_ALLOWED", 405, null));
    }

    /**
     * Catches unmatched request paths — Spring 6.1 replaced NoHandlerFoundException
     * with NoResourceFoundException for routes that don't match any controller.
     * Without this handler the catch-all returns 500; this returns the correct 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("No endpoint found for this request", "ENDPOINT_NOT_FOUND", 404, null));
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
