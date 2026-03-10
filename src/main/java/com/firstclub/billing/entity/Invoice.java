package com.firstclub.billing.entity;

import com.firstclub.billing.model.InvoiceStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InvoiceStatus status;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "due_date", nullable = false)
    private LocalDateTime dueDate;

    @Column(name = "period_start")
    private LocalDateTime periodStart;

    @Column(name = "period_end")
    private LocalDateTime periodEnd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Phase 8: merchant-scoped invoice numbering ──────────────────────────
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    // ── Phase 8: invoice total breakdown ────────────────────────────────────
    @Builder.Default
    @Column(name = "subtotal", nullable = false, precision = 18, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "discount_total", nullable = false, precision = 18, scale = 4)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "credit_total", nullable = false, precision = 18, scale = 4)
    private BigDecimal creditTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tax_total", nullable = false, precision = 18, scale = 4)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "grand_total", nullable = false, precision = 18, scale = 4)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    // ── Phase 17: invoice correctness guards ────────────────────────────────
    /** Effective credit applied in minor units (paise), kept separately from
     *  CREDIT_APPLIED lines so partial-apply and carry-forward are auditable. */
    @Builder.Default
    @Column(name = "effective_credit_applied_minor", nullable = false)
    private Long effectiveCreditAppliedMinor = 0L;

    /** For rebuilt/corrected invoices: the original invoice this was rebuilt from. */
    @Column(name = "source_invoice_id")
    private Long sourceInvoiceId;

    /** Timestamp of the last rebuild-totals action. */
    @Column(name = "rebuilt_at")
    private LocalDateTime rebuiltAt;

    /** Principal (username/email) who triggered the last rebuild. */
    @Column(name = "rebuilt_by", length = 255)
    private String rebuiltBy;
}
