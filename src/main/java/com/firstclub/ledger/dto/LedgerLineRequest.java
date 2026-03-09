package com.firstclub.ledger.dto;

import com.firstclub.ledger.entity.LineDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerLineRequest {

    private String accountName;
    private LineDirection direction;

    @Positive
    private BigDecimal amount;
}
