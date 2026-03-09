package com.firstclub.recon.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "external_statement_imports", indexes = {
        @Index(name = "idx_statement_imports_merchant_date", columnList = "merchant_id,statement_date")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ExternalStatementImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private StatementSourceType sourceType = StatementSourceType.GATEWAY;

    @Column(name = "statement_date", nullable = false)
    private LocalDate statementDate;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StatementImportStatus status = StatementImportStatus.PENDING;

    @Builder.Default
    @Column(name = "row_count", nullable = false)
    private int rowCount = 0;

    @Builder.Default
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
