package com.firstclub.billing.tax.dto;

import com.firstclub.billing.tax.entity.TaxMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxProfileCreateOrUpdateRequestDTO {

    /** 15-character GSTIN (basic format validated). */
    @NotBlank
    @Pattern(
        regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
        message = "Invalid GSTIN format"
    )
    private String gstin;

    /** 2-character ISO 3166-2:IN state code, e.g. MH, KA, DL. */
    @NotBlank
    @Size(min = 2, max = 8)
    private String legalStateCode;

    @NotBlank
    @Size(max = 255)
    private String registeredBusinessName;

    @NotNull
    private TaxMode taxMode;
}
