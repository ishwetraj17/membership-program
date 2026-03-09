package com.firstclub.billing.dto;

import com.firstclub.billing.entity.InvoiceLineType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceLineDTO {

    private Long id;
    private Long invoiceId;
    private InvoiceLineType lineType;
    private String description;
    /** Positive = charge, negative = credit. */
    private BigDecimal amount;
}
