package com.firstclub.dunning.service.impl;

import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceRequestDTO;
import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceResponseDTO;
import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import com.firstclub.dunning.service.SubscriptionPaymentPreferenceService;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionPaymentPreferenceServiceImpl implements SubscriptionPaymentPreferenceService {

    private final SubscriptionPaymentPreferenceRepository preferenceRepository;
    private final SubscriptionV2Repository subscriptionV2Repository;
    private final PaymentMethodRepository paymentMethodRepository;

    // ── setPaymentPreferences ─────────────────────────────────────────────────

    @Override
    @Transactional
    public SubscriptionPaymentPreferenceResponseDTO setPaymentPreferences(
            Long merchantId, Long subscriptionId,
            SubscriptionPaymentPreferenceRequestDTO request) {

        // 1. Resolve the subscription's customer (validates merchant ownership)
        Long customerId = subscriptionV2Repository
                .findCustomerIdByMerchantIdAndId(merchantId, subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription " + subscriptionId + " not found for merchant " + merchantId,
                        "SUBSCRIPTION_NOT_FOUND", HttpStatus.NOT_FOUND));

        // 2. Validate primary payment method
        validatePaymentMethod(merchantId, customerId, request.getPrimaryPaymentMethodId(),
                "primaryPaymentMethodId");

        // 3. Validate backup payment method (optional)
        if (request.getBackupPaymentMethodId() != null) {
            if (request.getBackupPaymentMethodId().equals(request.getPrimaryPaymentMethodId())) {
                throw new MembershipException(
                        "Primary and backup payment methods must be different",
                        "DUPLICATE_PAYMENT_METHODS", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            validatePaymentMethod(merchantId, customerId, request.getBackupPaymentMethodId(),
                    "backupPaymentMethodId");
        }

        // 4. Upsert preference
        SubscriptionPaymentPreference pref = preferenceRepository.findBySubscriptionId(subscriptionId)
                .map(existing -> {
                    existing.setPrimaryPaymentMethodId(request.getPrimaryPaymentMethodId());
                    existing.setBackupPaymentMethodId(request.getBackupPaymentMethodId());
                    existing.setRetryOrderJson(request.getRetryOrderJson());
                    return existing;
                })
                .orElse(SubscriptionPaymentPreference.builder()
                        .subscriptionId(subscriptionId)
                        .primaryPaymentMethodId(request.getPrimaryPaymentMethodId())
                        .backupPaymentMethodId(request.getBackupPaymentMethodId())
                        .retryOrderJson(request.getRetryOrderJson())
                        .build());

        SubscriptionPaymentPreferenceResponseDTO result = toDto(preferenceRepository.save(pref));
        log.info("Payment preferences set for subscription {} (primary={}, backup={})",
                subscriptionId, request.getPrimaryPaymentMethodId(), request.getBackupPaymentMethodId());
        return result;
    }

    // ── getPreferencesForSubscription ────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SubscriptionPaymentPreferenceResponseDTO getPreferencesForSubscription(
            Long merchantId, Long subscriptionId) {

        // Validate subscription belongs to merchant
        subscriptionV2Repository.findCustomerIdByMerchantIdAndId(merchantId, subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription " + subscriptionId + " not found for merchant " + merchantId,
                        "SUBSCRIPTION_NOT_FOUND", HttpStatus.NOT_FOUND));

        SubscriptionPaymentPreference pref = preferenceRepository.findBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "No payment preferences found for subscription " + subscriptionId,
                        "PREFERENCE_NOT_FOUND", HttpStatus.NOT_FOUND));

        return toDto(pref);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validatePaymentMethod(Long merchantId, Long customerId,
                                       Long pmId, String fieldName) {
        PaymentMethod pm = paymentMethodRepository
                .findByMerchantIdAndCustomerIdAndId(merchantId, customerId, pmId)
                .orElseThrow(() -> new MembershipException(
                        fieldName + ": payment method " + pmId
                        + " does not belong to customer " + customerId
                        + " or merchant " + merchantId,
                        "PAYMENT_METHOD_NOT_FOUND", HttpStatus.UNPROCESSABLE_ENTITY));

        if (pm.getStatus() != PaymentMethodStatus.ACTIVE) {
            throw new MembershipException(
                    fieldName + ": payment method " + pmId + " is not ACTIVE (status=" + pm.getStatus() + ")",
                    "PAYMENT_METHOD_NOT_ACTIVE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private SubscriptionPaymentPreferenceResponseDTO toDto(SubscriptionPaymentPreference p) {
        return SubscriptionPaymentPreferenceResponseDTO.builder()
                .id(p.getId())
                .subscriptionId(p.getSubscriptionId())
                .primaryPaymentMethodId(p.getPrimaryPaymentMethodId())
                .backupPaymentMethodId(p.getBackupPaymentMethodId())
                .retryOrderJson(p.getRetryOrderJson())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
