package com.firstclub.billing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "invoice_lines")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 32)
    private InvoiceLineType lineType;

    @Column(nullable = false, length = 255)
    private String description;

    /**
     * Positive amounts are charges; negative amounts are credits.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;
}
