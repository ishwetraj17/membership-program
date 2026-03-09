package com.firstclub.merchant.repository;

import com.firstclub.merchant.entity.MerchantUser;
import com.firstclub.merchant.entity.MerchantUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantUserRepository extends JpaRepository<MerchantUser, Long> {

    List<MerchantUser> findByMerchantId(Long merchantId);

    Optional<MerchantUser> findByMerchantIdAndUserId(Long merchantId, Long userId);

    boolean existsByMerchantIdAndUserId(Long merchantId, Long userId);

    long countByMerchantIdAndRole(Long merchantId, MerchantUserRole role);

    void deleteByMerchantIdAndUserId(Long merchantId, Long userId);
}
