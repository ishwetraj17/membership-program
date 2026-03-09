package com.firstclub.payments.dto;

import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.entity.PaymentMethodType;
import lombok.*;

import java.time.LocalDateTime;

/**
 * API response for a {@link com.firstclub.payments.entity.PaymentMethod}.
 *
 * <p>Contains the gateway-issued {@code providerToken} (opaque reference) and
 * non-sensitive display metadata.  Raw card PAN is never present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodResponseDTO {

    private Long id;
    private Long merchantId;
    private Long customerId;

    private PaymentMethodType methodType;

    /** Opaque gateway token — never a raw PAN. */
    private String providerToken;

    private String fingerprint;
    private String last4;
    private String brand;
    private String provider;

    private PaymentMethodStatus status;
    private boolean isDefault;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
