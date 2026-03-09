package com.firstclub.payments.service;

import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing customer payment methods (stored tokenized instruments).
 */
public interface PaymentMethodService {

    PaymentMethodResponseDTO createPaymentMethod(Long merchantId, Long customerId,
                                                  PaymentMethodCreateRequestDTO request);

    List<PaymentMethodResponseDTO> listCustomerPaymentMethods(Long merchantId, Long customerId);

    PaymentMethodResponseDTO setDefaultPaymentMethod(Long merchantId, Long customerId,
                                                      Long paymentMethodId);

    PaymentMethodResponseDTO revokePaymentMethod(Long merchantId, Long customerId,
                                                  Long paymentMethodId);

    Optional<PaymentMethodResponseDTO> getDefaultPaymentMethod(Long merchantId, Long customerId);
}
