package com.firstclub.billing.tax.dto;

import com.firstclub.billing.tax.entity.TaxMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxProfileResponseDTO {

    private Long id;
    private Long merchantId;
    private String gstin;
    private String legalStateCode;
    private String registeredBusinessName;
    private TaxMode taxMode;
    private LocalDateTime createdAt;
}
