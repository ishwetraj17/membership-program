package com.firstclub.recon.repository;

import com.firstclub.recon.entity.SettlementBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    Page<SettlementBatch> findByMerchantId(Long merchantId, Pageable pageable);

    Optional<SettlementBatch> findByMerchantIdAndBatchDate(Long merchantId, LocalDate batchDate);
}
