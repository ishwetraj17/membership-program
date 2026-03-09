package com.firstclub.payments.service.impl;

import com.firstclub.payments.dto.PaymentMethodMandateCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateResponseDTO;
import com.firstclub.payments.entity.MandateStatus;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodMandate;
import com.firstclub.payments.exception.PaymentMethodException;
import com.firstclub.payments.mapper.PaymentMethodMandateMapper;
import com.firstclub.payments.repository.PaymentMethodMandateRepository;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.service.PaymentMethodMandateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentMethodMandateServiceImpl implements PaymentMethodMandateService {

    private final PaymentMethodMandateRepository paymentMethodMandateRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final PaymentMethodMandateMapper paymentMethodMandateMapper;

    @Override
    public PaymentMethodMandateResponseDTO createMandate(Long merchantId, Long customerId,
                                                          Long paymentMethodId,
                                                          PaymentMethodMandateCreateRequestDTO request) {
        PaymentMethod pm = loadPaymentMethod(merchantId, customerId, paymentMethodId);

        if (!pm.getMethodType().supportsMandates()) {
            throw PaymentMethodException.unsupportedMandateType(pm.getMethodType());
        }
        if (!pm.getStatus().isUsable()) {
            throw PaymentMethodException.notUsable(paymentMethodId);
        }

        PaymentMethodMandate mandate = paymentMethodMandateMapper.toEntity(request);
        mandate.setPaymentMethod(pm);
        mandate.setStatus(MandateStatus.PENDING);

        PaymentMethodMandate saved = paymentMethodMandateRepository.save(mandate);
        log.info("Created mandate {} for payment method {} (customer {}, merchant {})",
                saved.getId(), paymentMethodId, customerId, merchantId);
        return paymentMethodMandateMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethodMandateResponseDTO> listMandates(Long merchantId, Long customerId,
                                                               Long paymentMethodId) {
        loadPaymentMethod(merchantId, customerId, paymentMethodId);

        return paymentMethodMandateRepository
                .findByPaymentMethodIdOrderByCreatedAtDesc(paymentMethodId)
                .stream()
                .map(paymentMethodMandateMapper::toResponseDTO)
                .toList();
    }

    @Override
    public PaymentMethodMandateResponseDTO revokeMandate(Long merchantId, Long customerId,
                                                          Long paymentMethodId, Long mandateId) {
        loadPaymentMethod(merchantId, customerId, paymentMethodId);

        PaymentMethodMandate mandate = paymentMethodMandateRepository
                .findByIdAndPaymentMethodId(mandateId, paymentMethodId)
                .orElseThrow(() -> PaymentMethodException.mandateNotFound(mandateId,
                        paymentMethodId));

        if (mandate.getStatus() == MandateStatus.REVOKED) {
            throw PaymentMethodException.mandateAlreadyRevoked(mandateId);
        }

        mandate.setStatus(MandateStatus.REVOKED);
        mandate.setRevokedAt(LocalDateTime.now());

        PaymentMethodMandate saved = paymentMethodMandateRepository.save(mandate);
        log.info("Revoked mandate {} for payment method {} (customer {}, merchant {})",
                mandateId, paymentMethodId, customerId, merchantId);
        return paymentMethodMandateMapper.toResponseDTO(saved);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private PaymentMethod loadPaymentMethod(Long merchantId, Long customerId,
                                             Long paymentMethodId) {
        return paymentMethodRepository
                .findByMerchantIdAndCustomerIdAndId(merchantId, customerId, paymentMethodId)
                .orElseThrow(() -> PaymentMethodException.notFound(merchantId, customerId,
                        paymentMethodId));
    }
}
