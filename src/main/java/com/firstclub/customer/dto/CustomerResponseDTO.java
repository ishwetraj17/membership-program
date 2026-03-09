package com.firstclub.customer.dto;

import com.firstclub.customer.entity.CustomerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO returned by all customer endpoints.
 * Encrypted PII fields are returned decrypted to authorised callers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerResponseDTO {

    private Long id;
    private Long merchantId;
    private String externalCustomerId;
    private String email;
    private String phone;
    private String fullName;
    private String billingAddress;
    private String shippingAddress;
    private CustomerStatus status;
    private Long defaultPaymentMethodId;
    private String metadataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
