package com.firstclub.merchant.repository;

import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantAccountRepository extends JpaRepository<MerchantAccount, Long> {

    Optional<MerchantAccount> findByMerchantCode(String merchantCode);

    boolean existsByMerchantCode(String merchantCode);

    Page<MerchantAccount> findByStatus(MerchantStatus status, Pageable pageable);

    Page<MerchantAccount> findAll(Pageable pageable);
}
