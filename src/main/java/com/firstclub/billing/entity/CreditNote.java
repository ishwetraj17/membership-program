package com.firstclub.billing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_notes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 10)
    private String currency;

    /** Full credit value. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 255)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Amount already consumed by applied invoices. Must be <= amount. */
    @Column(name = "used_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal usedAmount = BigDecimal.ZERO;

    /** Available balance that can still be applied. */
    @Transient
    public BigDecimal getAvailableBalance() {
        return amount.subtract(usedAmount);
    }
}
