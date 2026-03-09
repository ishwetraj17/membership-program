package com.firstclub.recon.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "recon_reports")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ReconReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_date", nullable = false, unique = true)
    private LocalDate reportDate;

    @Column(name = "expected_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal expectedTotal;

    @Column(name = "actual_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal actualTotal;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
