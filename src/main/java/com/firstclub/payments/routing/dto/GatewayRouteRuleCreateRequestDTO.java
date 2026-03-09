package com.firstclub.payments.routing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class GatewayRouteRuleCreateRequestDTO {

    private Long merchantId;

    @Positive
    private int priority;

    @NotBlank
    @Size(max = 32)
    private String paymentMethodType;

    @NotBlank
    @Size(max = 10)
    private String currency;

    @Size(max = 8)
    private String countryCode;

    @Min(1)
    private int retryNumber = 1;

    @NotBlank
    @Size(max = 64)
    private String preferredGateway;

    @Size(max = 64)
    private String fallbackGateway;

    public GatewayRouteRuleCreateRequestDTO() {}

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
}
