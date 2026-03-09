package com.firstclub.billing.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "discount_redemptions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscountRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discount_id", nullable = false)
    private Long discountId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @CreationTimestamp
    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private LocalDateTime redeemedAt;
}
