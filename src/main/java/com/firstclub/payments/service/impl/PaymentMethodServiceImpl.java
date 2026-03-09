package com.firstclub.payments.service.impl;

import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.exception.PaymentMethodException;
import com.firstclub.payments.mapper.PaymentMethodMapper;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.service.PaymentMethodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentMethodServiceImpl implements PaymentMethodService {

    private final PaymentMethodRepository paymentMethodRepository;
    private final MerchantAccountRepository merchantAccountRepository;
    private final CustomerRepository customerRepository;
    private final PaymentMethodMapper paymentMethodMapper;

    @Override
    public PaymentMethodResponseDTO createPaymentMethod(Long merchantId, Long customerId,
                                                         PaymentMethodCreateRequestDTO request) {
        var merchant = merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
        var customer = customerRepository.findByMerchantIdAndId(merchantId, customerId)
                .orElseThrow(() -> CustomerException.customerNotFound(merchantId, customerId));

        if (paymentMethodRepository.existsByProviderAndProviderToken(
                request.getProvider(), request.getProviderToken())) {
            throw PaymentMethodException.duplicateProviderToken(
                    request.getProvider(), request.getProviderToken());
        }

        PaymentMethod pm = paymentMethodMapper.toEntity(request);
        pm.setMerchant(merchant);
        pm.setCustomer(customer);
        pm.setStatus(PaymentMethodStatus.ACTIVE);

        boolean existingMethods = !paymentMethodRepository
                .findByMerchantIdAndCustomerId(merchantId, customerId).isEmpty();
        boolean shouldBeDefault = request.isMakeDefault() || !existingMethods;

        if (shouldBeDefault) {
            clearCurrentDefault(customerId);
            pm.setDefault(true);
        } else {
            pm.setDefault(false);
        }

        PaymentMethod saved = paymentMethodRepository.save(pm);
        log.info("Created payment method {} for customer {} under merchant {}",
                saved.getId(), customerId, merchantId);
        return paymentMethodMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethodResponseDTO> listCustomerPaymentMethods(Long merchantId,
                                                                      Long customerId) {
        merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
        customerRepository.findByMerchantIdAndId(merchantId, customerId)
                .orElseThrow(() -> CustomerException.customerNotFound(merchantId, customerId));

        return paymentMethodRepository.findByMerchantIdAndCustomerId(merchantId, customerId)
                .stream()
                .map(paymentMethodMapper::toResponseDTO)
                .toList();
    }

    @Override
    public PaymentMethodResponseDTO setDefaultPaymentMethod(Long merchantId, Long customerId,
                                                             Long paymentMethodId) {
        PaymentMethod pm = paymentMethodRepository
                .findByMerchantIdAndCustomerIdAndId(merchantId, customerId, paymentMethodId)
                .orElseThrow(() -> PaymentMethodException.notFound(merchantId, customerId,
                        paymentMethodId));

        if (!pm.getStatus().isUsable()) {
            throw PaymentMethodException.notUsable(paymentMethodId);
        }

        if (!pm.isDefault()) {
            clearCurrentDefault(customerId);
            pm.setDefault(true);
            pm = paymentMethodRepository.save(pm);
        }

        return paymentMethodMapper.toResponseDTO(pm);
    }

    @Override
    public PaymentMethodResponseDTO revokePaymentMethod(Long merchantId, Long customerId,
                                                         Long paymentMethodId) {
        PaymentMethod pm = paymentMethodRepository
                .findByMerchantIdAndCustomerIdAndId(merchantId, customerId, paymentMethodId)
                .orElseThrow(() -> PaymentMethodException.notFound(merchantId, customerId,
                        paymentMethodId));

        pm.setStatus(PaymentMethodStatus.REVOKED);
        if (pm.isDefault()) {
            pm.setDefault(false);
        }

        PaymentMethod saved = paymentMethodRepository.save(pm);
        log.info("Revoked payment method {} for customer {} under merchant {}",
                paymentMethodId, customerId, merchantId);
        return paymentMethodMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentMethodResponseDTO> getDefaultPaymentMethod(Long merchantId,
                                                                       Long customerId) {
        merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
        customerRepository.findByMerchantIdAndId(merchantId, customerId)
                .orElseThrow(() -> CustomerException.customerNotFound(merchantId, customerId));

        return paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(customerId)
                .map(paymentMethodMapper::toResponseDTO);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private void clearCurrentDefault(Long customerId) {
        paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(customerId)
                .ifPresent(old -> {
                    old.setDefault(false);
                    paymentMethodRepository.save(old);
                });
    }
}
