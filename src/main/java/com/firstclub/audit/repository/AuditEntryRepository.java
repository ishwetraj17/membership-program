package com.firstclub.audit.repository;

import com.firstclub.audit.entity.AuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AuditEntry}.
 *
 * <p>Audit entries are <em>append-only</em>: no update or delete methods are
 * intentionally exposed here.
 */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    /**
     * Retrieve all audit entries for a specific domain entity, newest first.
     */
    Page<AuditEntry> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
            String entityType, Long entityId, Pageable pageable);

    /**
     * Retrieve all audit entries for a merchant, newest first.
     */
    Page<AuditEntry> findByMerchantIdOrderByOccurredAtDesc(
            Long merchantId, Pageable pageable);

    /**
     * Compliance: retrieve all <em>failed</em> operations, newest first.
     */
    Page<AuditEntry> findByOperationTypeAndSuccessFalseOrderByOccurredAtDesc(
            String operationType, Pageable pageable);

    /**
     * Compliance: retrieve all failed entries regardless of operationType.
     */
    @Query("SELECT a FROM AuditEntry a WHERE a.success = false ORDER BY a.occurredAt DESC")
    Page<AuditEntry> findAllFailures(Pageable pageable);

    /**
     * Retrieve entries by correlation grouping (useful for distributed tracing).
     */
    Page<AuditEntry> findByCorrelationIdOrderByOccurredAtDesc(
            @Param("correlationId") String correlationId, Pageable pageable);
}
