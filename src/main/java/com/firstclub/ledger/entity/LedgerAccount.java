package com.firstclub.ledger.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A named account in the chart of accounts.
 *
 * <p>Core accounts are seeded on startup by {@link com.firstclub.ledger.config.AccountSeeder}:
 * <ul>
 *   <li>PG_CLEARING — ASSET (money held at the payment gateway)</li>
 *   <li>BANK — ASSET (settled funds in our bank account)</li>
 *   <li>SUBSCRIPTION_LIABILITY — LIABILITY (cash received, service not yet delivered)</li>
 *   <li>REVENUE_SUBSCRIPTIONS — INCOME (earned revenue after delivery)</li>
 * </ul>
 */
@Entity
@Table(name = "ledger_accounts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "INR";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Normal-balance convention drives the sign of the balance calculation. */
    public enum AccountType {
        /** Normal balance: DEBIT. Net = DEBIT - CREDIT. */
        ASSET,
        /** Normal balance: CREDIT. Net = CREDIT - DEBIT. */
        LIABILITY,
        /** Normal balance: CREDIT. Net = CREDIT - DEBIT. */
        INCOME,
        /** Normal balance: DEBIT. Net = DEBIT - CREDIT. */
        EXPENSE
    }
}
