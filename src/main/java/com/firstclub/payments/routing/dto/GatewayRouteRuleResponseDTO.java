package com.firstclub.payments.routing.dto;

import java.time.LocalDateTime;

public class GatewayRouteRuleResponseDTO {

    private Long id;
    private Long merchantId;
    private int priority;
    private String paymentMethodType;
    private String currency;
    private String countryCode;
    private int retryNumber;
    private String preferredGateway;
    private String fallbackGateway;
    private boolean active;
    private LocalDateTime createdAt;

    public GatewayRouteRuleResponseDTO() {}

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
