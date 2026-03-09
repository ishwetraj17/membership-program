package com.firstclub.payments.service;

import com.firstclub.payments.dto.PaymentMethodMandateCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateResponseDTO;

import java.util.List;

/**
 * Service for managing payment method mandates.
 */
public interface PaymentMethodMandateService {

    PaymentMethodMandateResponseDTO createMandate(Long merchantId, Long customerId,
                                                   Long paymentMethodId,
                                                   PaymentMethodMandateCreateRequestDTO request);

    List<PaymentMethodMandateResponseDTO> listMandates(Long merchantId, Long customerId,
                                                        Long paymentMethodId);

    PaymentMethodMandateResponseDTO revokeMandate(Long merchantId, Long customerId,
                                                   Long paymentMethodId, Long mandateId);
}
