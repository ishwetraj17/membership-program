package com.firstclub.platform.version.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Request body for {@code PUT /merchants/{merchantId}/api-version}.
 *
 * @param version       API version string in {@code YYYY-MM-DD} format (required)
 * @param effectiveFrom date from which the pin is active; defaults to today if omitted
 */
public record ApiVersionPinRequestDTO(

        @NotBlank(message = "version must not be blank")
        @Pattern(
            regexp  = "^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$",
            message = "version must be in YYYY-MM-DD format"
        )
        String version,

        LocalDate effectiveFrom
) {}
