package com.firstclub.recon.entity;

import com.firstclub.recon.classification.ReconExpectation;
import com.firstclub.recon.classification.ReconSeverity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "recon_mismatches", indexes = {
        @Index(name = "idx_recon_mismatches_report", columnList = "report_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ReconMismatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MismatchType type;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReconMismatchStatus status = ReconMismatchStatus.OPEN;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    // ── Phase 14: taxonomy, expectation classification, and FX fields ─────────

    /** Expected vs unexpected categorisation, set by {@link com.firstclub.recon.classification.ReconExpectationClassifier}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ReconExpectation expectation;

    /** Operational severity used for alerting. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ReconSeverity severity;

    /** Gateway transaction ID — the recon anchor when present. */
    @Column(name = "gateway_transaction_id", length = 128)
    private String gatewayTransactionId;

    /** Merchant the mismatch belongs to (may be null if not determinable). */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** Charge currency of the affected payment. */
    @Column(length = 10)
    private String currency;

    /** Settlement currency (may differ from charge currency in cross-border scenarios). */
    @Column(name = "settlement_currency", length = 3)
    private String settlementCurrency;

    /** FX exchange rate applied at settlement time (charge → settlement currency). */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal fxRate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
