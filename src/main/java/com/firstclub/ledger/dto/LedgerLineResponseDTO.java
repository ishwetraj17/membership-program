package com.firstclub.ledger.dto;

import com.firstclub.ledger.entity.LineDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Phase 10 — Read-only view of a single {@code LedgerLine}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerLineResponseDTO {

    private Long          id;
    private Long          entryId;
    private Long          accountId;
    private String        accountName;
    private LineDirection direction;
    private BigDecimal    amount;
}
