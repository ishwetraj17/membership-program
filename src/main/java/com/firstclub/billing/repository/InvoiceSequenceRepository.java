package com.firstclub.billing.repository;

import com.firstclub.billing.entity.InvoiceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, Long> {

    Optional<InvoiceSequence> findByMerchantId(Long merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InvoiceSequence s WHERE s.merchantId = :merchantId")
    Optional<InvoiceSequence> findByMerchantIdWithLock(@Param("merchantId") Long merchantId);
}
