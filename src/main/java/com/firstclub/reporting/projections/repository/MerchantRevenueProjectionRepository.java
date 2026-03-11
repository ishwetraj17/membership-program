package com.firstclub.reporting.projections.repository;

import com.firstclub.reporting.projections.entity.MerchantRevenueProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface MerchantRevenueProjectionRepository
        extends JpaRepository<MerchantRevenueProjection, Long> {

    @Query("SELECT MIN(p.updatedAt) FROM MerchantRevenueProjection p")
    Optional<LocalDateTime> findOldestUpdatedAt();
}
