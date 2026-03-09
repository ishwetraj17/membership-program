package com.firstclub.billing.tax.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

/** Request body for the POST /recalculate-tax endpoint. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecalculateTaxRequestDTO {

    /** The customer whose tax profile governs the GST decision. */
    @NotNull
    private Long customerId;
}
