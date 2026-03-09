package com.firstclub.dunning.adapter;

import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.payments.service.PaymentIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default (simulated) gateway implementation for dev and testing environments.
 *
 * <p>Drives the PaymentIntent state machine through PROCESSING → FAILED to
 * simulate a declined charge.  In tests, replace this bean with a
 * {@code @MockBean PaymentGatewayPort} to control outcomes precisely.
 *
 * <p>Replace this bean with a real gateway adapter before deploying to production.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SimulatedPaymentGateway implements PaymentGatewayPort {

    private final PaymentIntentService paymentIntentService;

    @Override
    public ChargeOutcome charge(Long paymentIntentId) {
        try {
            paymentIntentService.markProcessing(paymentIntentId);
            paymentIntentService.markFailed(paymentIntentId);
            log.debug("Simulated charge for PI {} → FAILED", paymentIntentId);
            return ChargeOutcome.FAILED;
        } catch (Exception e) {
            log.error("Simulated charge error for PI {}: {}", paymentIntentId, e.getMessage(), e);
            return ChargeOutcome.FAILED;
        }
    }
}
