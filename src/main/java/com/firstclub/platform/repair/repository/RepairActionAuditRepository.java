package com.firstclub.platform.repair.repository;

import com.firstclub.platform.repair.entity.RepairActionAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepairActionAuditRepository extends JpaRepository<RepairActionAudit, Long> {

    Page<RepairActionAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<RepairActionAudit> findByRepairKeyOrderByCreatedAtDesc(String repairKey);

    List<RepairActionAudit> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);

    List<RepairActionAudit> findByActorUserIdOrderByCreatedAtDesc(Long actorUserId);
}
