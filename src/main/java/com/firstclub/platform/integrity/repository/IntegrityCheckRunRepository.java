package com.firstclub.platform.integrity.repository;

import com.firstclub.platform.integrity.entity.IntegrityCheckRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository("platformIntegrityCheckRunRepository")
public interface IntegrityCheckRunRepository extends JpaRepository<IntegrityCheckRun, Long> {

    List<IntegrityCheckRun> findAllByOrderByStartedAtDesc();

    List<IntegrityCheckRun> findByMerchantIdOrderByStartedAtDesc(Long merchantId);

    List<IntegrityCheckRun> findByStatusOrderByStartedAtDesc(String status);

    /** Returns the most recent completed run, or empty if no runs exist yet. */
    java.util.Optional<IntegrityCheckRun> findFirstByOrderByStartedAtDesc();
}
