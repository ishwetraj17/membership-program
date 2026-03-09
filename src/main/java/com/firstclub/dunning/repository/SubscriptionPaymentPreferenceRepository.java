package com.firstclub.dunning.repository;

import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionPaymentPreferenceRepository
        extends JpaRepository<SubscriptionPaymentPreference, Long> {

    Optional<SubscriptionPaymentPreference> findBySubscriptionId(Long subscriptionId);
}
