package com.firstclub.ledger.dto;

import com.firstclub.ledger.entity.LedgerAccount;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerAccountBalanceDTO {

    /** Database PK of the ledger account — populated by {@code LedgerService.getBalances()}. */
    private Long accountId;

    private String accountName;
    private LedgerAccount.AccountType accountType;
    private String currency;
    private BigDecimal debitTotal;
    private BigDecimal creditTotal;

    /**
     * Normal-balance perspective:
     * ASSET / EXPENSE  → balance = debitTotal - creditTotal
     * LIABILITY / INCOME → balance = creditTotal - debitTotal
     */
    private BigDecimal balance;
}
