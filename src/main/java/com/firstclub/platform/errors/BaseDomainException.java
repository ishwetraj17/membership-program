package com.firstclub.platform.errors;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Root of the platform domain exception hierarchy.
 *
 * <h3>Design rationale</h3>
 * <ul>
 *   <li><strong>Machine-readable errorCode</strong> — clients branch on
 *       {@code errorCode}, never on {@code message} strings.  Stable across
 *       releases.</li>
 *   <li><strong>httpStatus</strong> — the {@code GlobalExceptionHandler} reads
 *       this field directly. No instanceof chains, no mapping tables.</li>
 *   <li><strong>metadata</strong> — structured key-value context (entity IDs,
 *       field names, amounts) for enriched error responses and log correlation.
 *       Never put raw passwords or PII here.</li>
 * </ul>
 *
 * <h3>Exception hierarchy</h3>
 * <pre>
 * BaseDomainException
 *  ├── IdempotencyConflictException   (409 — key conflict or in-flight)
 *  ├── InvariantViolationException    (500 — accounting / state invariant broken)
 *  ├── StaleOperationException        (409 — entity state changed under async op)
 *  └── RequestInFlightException       (409 — concurrent operation on same entity)
 *
 * Existing (pre-Phase-1, not yet migrated):
 *  MembershipException                (module-level, no common base)
 *  ConcurrencyConflictException       (platform/concurrency, no common base)
 *  … etc.
 * </pre>
 *
 * <h3>Migration note</h3>
 * Module-level exceptions ({@code MembershipException}, {@code SubscriptionException},
 * etc.) pre-date this base class.  They will be migrated to extend
 * {@code BaseDomainException} incrementally in later phases without breaking
 * the existing {@code GlobalExceptionHandler} handlers.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   throw new InvariantViolationException(
 *       "LEDGER_UNBALANCED",
 *       "Debit total does not equal credit total",
 *       Map.of("debit", debitMinor, "credit", creditMinor));
 * }</pre>
 */
public abstract class BaseDomainException extends RuntimeException {

    /** Machine-readable error identifier. Stable, uppercase with underscores. */
    private final String errorCode;

    /** HTTP status to return to the caller. */
    private final HttpStatus httpStatus;

    /**
     * Optional structured metadata for logging and error response enrichment.
     * Immutable after construction.
     */
    private final Map<String, Object> metadata;

    // ── Constructors ─────────────────────────────────────────────────────────

    protected BaseDomainException(String errorCode,
                                   String message,
                                   HttpStatus httpStatus) {
        super(message);
        this.errorCode  = Objects.requireNonNull(errorCode,  "errorCode must not be null");
        this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus must not be null");
        this.metadata   = Collections.emptyMap();
    }

    protected BaseDomainException(String errorCode,
                                   String message,
                                   HttpStatus httpStatus,
                                   Map<String, Object> metadata) {
        super(message);
        this.errorCode  = Objects.requireNonNull(errorCode,  "errorCode must not be null");
        this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus must not be null");
        this.metadata   = metadata != null
                ? Collections.unmodifiableMap(new HashMap<>(metadata))
                : Collections.emptyMap();
    }

    protected BaseDomainException(String errorCode,
                                   String message,
                                   HttpStatus httpStatus,
                                   Throwable cause) {
        super(message, cause);
        this.errorCode  = Objects.requireNonNull(errorCode,  "errorCode must not be null");
        this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus must not be null");
        this.metadata   = Collections.emptyMap();
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Stable machine-readable error code, e.g. {@code LEDGER_UNBALANCED}. */
    public String getErrorCode() {
        return errorCode;
    }

    /** HTTP status to return. Read directly by {@code GlobalExceptionHandler}. */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * Structured key-value details for logging and error-response enrichment.
     * Never contains sensitive data (PII, credentials).
     * Returns an immutable map — never null.
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
