package com.firstclub.membership.service;

import com.firstclub.membership.service.impl.NoOpPaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo adapter must model PSP idempotency: the same idempotency key yields the same reference
 * (a retry is deduplicated), and different keys yield different references.
 */
@DisplayName("NoOp payment adapter — idempotency-key determinism")
class NoOpPaymentGatewayTest {

    private final NoOpPaymentGateway gateway = new NoOpPaymentGateway();

    private ChargeRequest charge(String key) {
        return ChargeRequest.builder()
                .idempotencyKey(key).correlationId("c").customerReference("1")
                .amount(new BigDecimal("499")).currency("INR").description("d").build();
    }

    @Test @DisplayName("same key → same reference (retry-safe)")
    void sameKeySameReference() {
        String first = gateway.charge(charge("charge:create:1")).reference();
        String replay = gateway.charge(charge("charge:create:1")).reference();
        assertThat(first).isEqualTo(replay);
    }

    @Test @DisplayName("different key → different reference")
    void differentKeyDifferentReference() {
        String a = gateway.charge(charge("charge:create:1")).reference();
        String b = gateway.charge(charge("charge:create:2")).reference();
        assertThat(a).isNotEqualTo(b);
    }
}
