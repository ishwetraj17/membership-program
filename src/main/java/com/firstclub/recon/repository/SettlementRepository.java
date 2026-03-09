package com.firstclub.recon.repository;

import com.firstclub.recon.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findBySettlementDate(LocalDate settlementDate);

    boolean existsBySettlementDate(LocalDate settlementDate);
}
