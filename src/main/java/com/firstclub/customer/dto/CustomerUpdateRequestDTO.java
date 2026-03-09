package com.firstclub.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating mutable fields of an existing customer.
 * All fields are optional; only non-null values are applied (patch semantics).
 * Email and externalCustomerId uniqueness is re-validated within the merchant if changed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerUpdateRequestDTO {

    @Size(max = 255)
    private String fullName;

    @Email(message = "Email must be a valid address")
    @Size(max = 255)
    private String email;

    @Size(max = 128)
    private String externalCustomerId;

    private String phone;

    private String billingAddress;

    private String shippingAddress;

    private String metadataJson;
}
