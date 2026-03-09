package com.firstclub.recon.repository;

import com.firstclub.recon.entity.ExternalStatementImport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExternalStatementImportRepository extends JpaRepository<ExternalStatementImport, Long> {

    Page<ExternalStatementImport> findByMerchantId(Long merchantId, Pageable pageable);

    Optional<ExternalStatementImport> findByMerchantIdAndStatementDate(Long merchantId, LocalDate statementDate);
}
