package com.firstclub.merchant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code PUT /api/v2/admin/merchants/{id}}.
 *
 * <p>All fields are optional; only non-null values are applied.
 * {@code merchantCode} is intentionally absent — it is immutable after creation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantUpdateRequestDTO {

    @Size(max = 255, message = "legal_name must not exceed 255 characters")
    private String legalName;

    @Size(max = 255, message = "display_name must not exceed 255 characters")
    private String displayName;

    @Email(message = "support_email must be a valid email address")
    private String supportEmail;

    @Size(max = 64, message = "timezone must not exceed 64 characters")
    private String timezone;

    @Size(max = 10, message = "default_currency must not exceed 10 characters")
    private String defaultCurrency;
}
