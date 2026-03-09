package com.firstclub.billing.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "invoice_sequences")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSequence {

    @Id
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "current_number", nullable = false)
    @Builder.Default
    private Long currentNumber = 0L;

    @Column(name = "prefix", nullable = false, length = 32)
    private String prefix;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = LocalDateTime.now();
    }
}
