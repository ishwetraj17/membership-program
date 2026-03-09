package com.firstclub.recon.repository;

import com.firstclub.recon.entity.SettlementBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementBatchItemRepository extends JpaRepository<SettlementBatchItem, Long> {

    List<SettlementBatchItem> findByBatchId(Long batchId);

    List<SettlementBatchItem> findByPaymentId(Long paymentId);
}
