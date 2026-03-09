package com.firstclub.platform.integrity.repository;

import com.firstclub.platform.integrity.entity.IntegrityCheckRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrityCheckRunRepository extends JpaRepository<IntegrityCheckRun, Long> {

    List<IntegrityCheckRun> findAllByOrderByStartedAtDesc();

    List<IntegrityCheckRun> findByMerchantIdOrderByStartedAtDesc(Long merchantId);

    List<IntegrityCheckRun> findByStatusOrderByStartedAtDesc(String status);

    /** Returns the most recent completed run, or empty if no runs exist yet. */
    java.util.Optional<IntegrityCheckRun> findFirstByOrderByStartedAtDesc();
}
