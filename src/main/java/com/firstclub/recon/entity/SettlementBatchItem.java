package com.firstclub.recon.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_batch_items", indexes = {
        @Index(name = "idx_settlement_batch_items_batch_id", columnList = "batch_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class SettlementBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "fee_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "reserve_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal reserveAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
