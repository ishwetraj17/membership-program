package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.AuditLogDTO;
import com.firstclub.membership.entity.AuditLog;
import com.firstclub.membership.repository.AuditLogRepository;
import com.firstclub.membership.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link AuditLogService}.
 *
 * <p>The write path ({@link #record}) is intentionally guarded by a broad
 * {@code try/catch}: audit logging must never disrupt the primary business
 * flow, so failures are logged as warnings rather than propagated.
 *
 * Implemented by Shwet Raj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public void record(AuditLog.AuditAction action, String entityType, Long entityId,
                       Long userId, String description, String metadata) {
        try {
            AuditLog entry = AuditLog.builder()
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .userId(userId)
                    .description(description)
                    .metadata(metadata)
                    .requestId(MDC.get("requestId"))
                    .build();
            auditLogRepository.save(entry);
            log.debug("Audit recorded: action={}, entity={}/{}", action, entityType, entityId);
        } catch (Exception ex) {
            log.warn("Failed to write audit log [{} {}/{}]: {}", action, entityType, entityId, ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogDTO> getAuditLogs(Pageable pageable) {
        return auditLogRepository.findAllByOrderByOccurredAtDesc(pageable).map(this::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogDTO> getAuditLogsForEntity(String entityType, Long entityId, Pageable pageable) {
        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId, pageable)
                .map(this::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogDTO> getAuditLogsForUser(Long userId, Pageable pageable) {
        return auditLogRepository.findByUserIdOrderByOccurredAtDesc(userId, pageable).map(this::toDTO);
    }

    private AuditLogDTO toDTO(AuditLog entry) {
        return AuditLogDTO.builder()
                .id(entry.getId())
                .action(entry.getAction())
                .entityType(entry.getEntityType())
                .entityId(entry.getEntityId())
                .userId(entry.getUserId())
                .description(entry.getDescription())
                .metadata(entry.getMetadata())
                .requestId(entry.getRequestId())
                .occurredAt(entry.getOccurredAt())
                .build();
    }
}
