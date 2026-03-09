package com.firstclub.billing.tax.dto;

import com.firstclub.billing.tax.entity.CustomerEntityType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerTaxProfileResponseDTO {

    private Long id;
    private Long customerId;
    private String gstin;
    private String stateCode;
    private CustomerEntityType entityType;
    private boolean taxExempt;
    private LocalDateTime createdAt;
}
