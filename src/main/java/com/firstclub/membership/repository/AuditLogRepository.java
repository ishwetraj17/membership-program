package com.firstclub.membership.repository;

import com.firstclub.membership.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link AuditLog} records.
 *
 * All queries are read-only by convention — audit entries are never updated
 * or hard-deleted after they are written.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** All entries ordered newest-first. */
    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    /** All entries for a specific entity (e.g. all events on subscription #42). */
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
            String entityType, Long entityId, Pageable pageable);

    /** All entries initiated by a specific user. */
    Page<AuditLog> findByUserIdOrderByOccurredAtDesc(Long userId, Pageable pageable);
}
