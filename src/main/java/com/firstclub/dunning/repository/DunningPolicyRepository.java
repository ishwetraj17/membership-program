package com.firstclub.dunning.repository;

import com.firstclub.dunning.entity.DunningPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DunningPolicyRepository extends JpaRepository<DunningPolicy, Long> {

    Optional<DunningPolicy> findByMerchantIdAndPolicyCode(Long merchantId, String policyCode);

    List<DunningPolicy> findByMerchantId(Long merchantId);
}
