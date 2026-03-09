package com.firstclub.merchant.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/v2/admin/merchants}.
 *
 * <p>{@code merchantCode} must be upper-snake-case (A-Z, 0-9, underscore) and is
 * immutable once created.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantCreateRequestDTO {

    @NotBlank(message = "merchant_code is required")
    @Pattern(
        regexp = "^[A-Z0-9_]{2,64}$",
        message = "merchant_code must be 2-64 characters, uppercase letters, digits and underscores only"
    )
    private String merchantCode;

    @NotBlank(message = "legal_name is required")
    @Size(max = 255, message = "legal_name must not exceed 255 characters")
    private String legalName;

    @NotBlank(message = "display_name is required")
    @Size(max = 255, message = "display_name must not exceed 255 characters")
    private String displayName;

    @NotBlank(message = "support_email is required")
    @Email(message = "support_email must be a valid email address")
    private String supportEmail;

    @NotBlank(message = "country_code is required")
    @Size(min = 2, max = 8, message = "country_code must be 2-8 characters")
    private String countryCode;

    @NotBlank(message = "timezone is required")
    @Size(max = 64, message = "timezone must not exceed 64 characters")
    private String timezone;

    /** Optional — defaults to "INR" if not provided. */
    @Size(max = 10, message = "default_currency must not exceed 10 characters")
    private String defaultCurrency;
}
