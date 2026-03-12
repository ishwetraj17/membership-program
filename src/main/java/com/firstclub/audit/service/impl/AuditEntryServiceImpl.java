package com.firstclub.audit.service.impl;

import com.firstclub.audit.dto.AuditEntryDTO;
import com.firstclub.audit.entity.AuditEntry;
import com.firstclub.audit.repository.AuditEntryRepository;
import com.firstclub.audit.service.AuditEntryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AuditEntryService} implementation.
 *
 * <h2>Transaction strategy</h2>
 * {@link #record} uses {@link Propagation#REQUIRES_NEW} so that the audit
 * entry is committed to the database even when the caller's transaction is
 * being rolled back (e.g. after a caught exception in the aspect).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEntryServiceImpl implements AuditEntryService {

    private static final int MAX_FAILURE_REASON_LENGTH = 2_000;

    private final AuditEntryRepository auditEntryRepository;

    // ── Write path ────────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEntry record(
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
    ) {
        AuditEntry entry = AuditEntry.builder()
                .operationType(operationType)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .performedBy(performedBy)
                .success(success)
                .failureReason(truncate(failureReason, MAX_FAILURE_REASON_LENGTH))
                .requestId(requestId)
                .correlationId(correlationId)
                .merchantId(merchantId)
                .actorId(actorId)
                .apiVersion(apiVersion)
                .ipAddress(ipAddress)
                .build();

        AuditEntry saved = auditEntryRepository.save(entry);

        if (!success) {
            log.warn("audit[FAILURE] op={} entity={}/{} actor={} merchant={} reason={}",
                    operationType, entityType, entityId, actorId, merchantId,
                    truncate(failureReason, 200));
        } else {
            log.debug("audit[OK] op={} entity={}/{} actor={} merchant={}",
                    operationType, entityType, entityId, actorId, merchantId);
        }

        return saved;
    }

    // ── Read path ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEntryDTO> findByEntity(String entityType, Long entityId, Pageable pageable) {
        return auditEntryRepository
                .findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId, pageable)
                .map(AuditEntryDTO::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEntryDTO> findByMerchant(Long merchantId, Pageable pageable) {
        return auditEntryRepository
                .findByMerchantIdOrderByOccurredAtDesc(merchantId, pageable)
                .map(AuditEntryDTO::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEntryDTO> findFailures(Pageable pageable) {
        return auditEntryRepository
                .findAllFailures(pageable)
                .map(AuditEntryDTO::from);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
