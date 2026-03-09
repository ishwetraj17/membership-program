package com.firstclub.payments.routing.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "gateway_route_rules")
public class GatewayRouteRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(nullable = false)
    private int priority;

    @Column(name = "payment_method_type", nullable = false, length = 32)
    private String paymentMethodType;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "country_code", length = 8)
    private String countryCode;

    @Column(name = "retry_number", nullable = false)
    private int retryNumber = 1;

    @Column(name = "preferred_gateway", nullable = false, length = 64)
    private String preferredGateway;

    @Column(name = "fallback_gateway", length = 64)
    private String fallbackGateway;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public GatewayRouteRule() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public String getPaymentMethodType() { return paymentMethodType; }
    public void setPaymentMethodType(String paymentMethodType) { this.paymentMethodType = paymentMethodType; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public int getRetryNumber() { return retryNumber; }
    public void setRetryNumber(int retryNumber) { this.retryNumber = retryNumber; }

    public String getPreferredGateway() { return preferredGateway; }
    public void setPreferredGateway(String preferredGateway) { this.preferredGateway = preferredGateway; }

    public String getFallbackGateway() { return fallbackGateway; }
    public void setFallbackGateway(String fallbackGateway) { this.fallbackGateway = fallbackGateway; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
