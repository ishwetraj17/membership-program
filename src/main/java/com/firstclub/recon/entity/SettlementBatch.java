package com.firstclub.recon.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_batches", indexes = {
        @Index(name = "idx_settlement_batches_merchant_date", columnList = "merchant_id,batch_date")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "batch_date", nullable = false)
    private LocalDate batchDate;

    @Column(name = "gateway_name", nullable = false, length = 64)
    private String gatewayName;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "fee_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "reserve_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal reserveAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "INR";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SettlementBatchStatus status = SettlementBatchStatus.CREATED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
