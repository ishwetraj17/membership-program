package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.SubscriptionHistoryDTO;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.SubscriptionHistory;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.SubscriptionHistoryRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.SubscriptionHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link SubscriptionHistoryService}.
 *
 * <p>History entries are written by {@code MembershipServiceImpl} during each
 * subscription state transition; this service only provides the read path.
 *
 * Implemented by Shwet Raj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionHistoryServiceImpl implements SubscriptionHistoryService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHistoryRepository subscriptionHistoryRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionHistoryDTO> getHistoryBySubscriptionId(Long subscriptionId, Pageable pageable) {
        log.debug("Fetching paged history for subscription {}", subscriptionId);
        Subscription subscription = findOrThrow(subscriptionId);
        return subscriptionHistoryRepository
                .findBySubscriptionOrderByChangedAtDesc(subscription, pageable)
                .map(this::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionHistoryDTO> getHistoryBySubscriptionId(Long subscriptionId) {
        log.debug("Fetching full history for subscription {}", subscriptionId);
        Subscription subscription = findOrThrow(subscriptionId);
        return subscriptionHistoryRepository
                .findBySubscriptionOrderByChangedAtDesc(subscription)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------

    private Subscription findOrThrow(Long subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription not found with id: " + subscriptionId,
                        "SUBSCRIPTION_NOT_FOUND"));
    }

    private SubscriptionHistoryDTO toDTO(SubscriptionHistory h) {
        return SubscriptionHistoryDTO.builder()
                .id(h.getId())
                .subscriptionId(h.getSubscription().getId())
                .eventType(h.getEventType())
                .oldPlanId(h.getOldPlanId())
                .newPlanId(h.getNewPlanId())
                .oldStatus(h.getOldStatus())
                .newStatus(h.getNewStatus())
                .reason(h.getReason())
                .changedByUserId(h.getChangedByUserId())
                .changedAt(h.getChangedAt())
                .build();
    }
}
