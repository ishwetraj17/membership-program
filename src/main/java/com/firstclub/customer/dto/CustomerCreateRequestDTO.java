package com.firstclub.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new customer under a merchant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerCreateRequestDTO {

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 255)
    private String email;

    /** Optional external CRM identifier — must be unique within the merchant if provided. */
    @Size(max = 128)
    private String externalCustomerId;

    private String phone;

    private String billingAddress;

    private String shippingAddress;

    private String metadataJson;
}
