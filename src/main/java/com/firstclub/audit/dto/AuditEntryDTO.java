package com.firstclub.audit.dto;

import com.firstclub.audit.entity.AuditEntry;

import java.time.LocalDateTime;

/**
 * Read-only projection of {@link AuditEntry} returned by the audit REST API.
 *
 * <p>The record is intentionally flat so clients do not need to navigate
 * nested objects for basic compliance queries.
 */
public record AuditEntryDTO(
        Long   id,
        String requestId,
        String correlationId,
        Long   merchantId,
        String actorId,
        String apiVersion,
        String operationType,
        String action,
        String performedBy,
        String entityType,
        Long   entityId,
        boolean success,
        String failureReason,
        Long   amountMinor,
        String currencyCode,
        String ipAddress,
        LocalDateTime occurredAt
) {

    /**
     * Factory method — converts a JPA entity to this DTO without a mapper dependency.
     */
    public static AuditEntryDTO from(AuditEntry e) {
        return new AuditEntryDTO(
                e.getId(),
                e.getRequestId(),
                e.getCorrelationId(),
                e.getMerchantId(),
                e.getActorId(),
                e.getApiVersion(),
                e.getOperationType(),
                e.getAction(),
                e.getPerformedBy(),
                e.getEntityType(),
                e.getEntityId(),
                e.isSuccess(),
                e.getFailureReason(),
                e.getAmountMinor(),
                e.getCurrencyCode(),
                e.getIpAddress(),
                e.getOccurredAt()
        );
    }
}
