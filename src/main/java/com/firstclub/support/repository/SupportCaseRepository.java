package com.firstclub.support.repository;

import com.firstclub.support.entity.SupportCase;
import com.firstclub.support.entity.SupportCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupportCaseRepository extends JpaRepository<SupportCase, Long> {

    List<SupportCase> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    List<SupportCase> findByMerchantIdAndStatusOrderByCreatedAtDesc(
            Long merchantId, SupportCaseStatus status);

    List<SupportCase> findByMerchantIdAndLinkedEntityTypeAndLinkedEntityIdOrderByCreatedAtDesc(
            Long merchantId, String linkedEntityType, Long linkedEntityId);

    List<SupportCase> findByMerchantIdAndLinkedEntityTypeAndLinkedEntityIdAndStatusOrderByCreatedAtDesc(
            Long merchantId, String linkedEntityType, Long linkedEntityId, SupportCaseStatus status);
}
