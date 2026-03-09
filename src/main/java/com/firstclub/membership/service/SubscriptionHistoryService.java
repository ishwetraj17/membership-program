package com.firstclub.membership.service;

import com.firstclub.membership.dto.SubscriptionHistoryDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Read-only service for the {@code subscription_history} audit trail.
 *
 * <p>History entries are written directly by {@code MembershipServiceImpl} as part
 * of each subscription state transition.  This service exposes them as a paged
 * or complete list for consumption by {@code SubscriptionHistoryController}.
 *
 * Implemented by Shwet Raj
 */
public interface SubscriptionHistoryService {

    /**
     * Return paginated history entries for the given subscription, newest first.
     *
     * @param subscriptionId PK of the subscription
     * @param pageable       pagination and sort configuration
     * @throws com.firstclub.membership.exception.MembershipException if the
     *         subscription does not exist
     */
    Page<SubscriptionHistoryDTO> getHistoryBySubscriptionId(Long subscriptionId, Pageable pageable);

    /**
     * Return the complete (unpaged) history for the given subscription, newest first.
     *
     * @param subscriptionId PK of the subscription
     * @throws com.firstclub.membership.exception.MembershipException if the
     *         subscription does not exist
     */
    List<SubscriptionHistoryDTO> getHistoryBySubscriptionId(Long subscriptionId);
}
