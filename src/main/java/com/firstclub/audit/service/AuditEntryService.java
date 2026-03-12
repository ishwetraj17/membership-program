package com.firstclub.audit.service;

import com.firstclub.audit.dto.AuditEntryDTO;
import com.firstclub.audit.entity.AuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Contract for recording and querying compliance-grade financial audit entries.
 *
 * <p>Implementations must guarantee that every call to {@link #record} persists
 * an {@link AuditEntry} even when the advised operation failed — the entry is
 * the evidence.
 */
public interface AuditEntryService {

    /**
     * Persist one immutable audit record.
     *
     * @param operationType  machine-readable operation constant
     * @param action         human-readable description
     * @param entityType     domain entity class name
     * @param entityId       PK of the mutated entity (may be {@code null})
     * @param performedBy    actor string (user id, service name, or job name)
     * @param success        {@code true} when the operation committed
     * @param failureReason  trimmed exception message when {@code success=false}
     * @param requestId      HTTP/gRPC request trace id
     * @param correlationId  distributed correlation id
     * @param merchantId     tenant merchant id
     * @param actorId        request context actor id (may duplicate performedBy)
     * @param apiVersion     negotiated API version string
     * @param ipAddress      originating client IP
     * @return the persisted entity (id is set)
     */
    AuditEntry record(
            String  operationType,
            String  action,
            String  entityType,
            Long    entityId,
            String  performedBy,
            boolean success,
            String  failureReason,
            String  requestId,
            String  correlationId,
            Long    merchantId,
            String  actorId,
            String  apiVersion,
            String  ipAddress
    );

    /**
     * Paginated query by entity — used by compliance GET /audit/entries.
     */
    Page<AuditEntryDTO> findByEntity(String entityType, Long entityId, Pageable pageable);

    /**
     * Paginated query by merchant, newest first.
     */
    Page<AuditEntryDTO> findByMerchant(Long merchantId, Pageable pageable);

    /**
     * Paginated query for all failed operations.
     */
    Page<AuditEntryDTO> findFailures(Pageable pageable);
}
