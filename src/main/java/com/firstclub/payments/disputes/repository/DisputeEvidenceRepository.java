package com.firstclub.payments.disputes.repository;

import com.firstclub.payments.disputes.entity.DisputeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, Long> {

    /** All evidence for a dispute, ordered oldest-first for chronological display. */
    List<DisputeEvidence> findByDisputeIdOrderByCreatedAtAsc(Long disputeId);
}
