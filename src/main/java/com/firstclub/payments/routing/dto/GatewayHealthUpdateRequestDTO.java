package com.firstclub.payments.routing.dto;

import com.firstclub.payments.routing.entity.GatewayHealthStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class GatewayHealthUpdateRequestDTO {

    @NotNull
    private GatewayHealthStatus status;

    @DecimalMin("0.00")
    @DecimalMax("100.00")
    private BigDecimal rollingSuccessRate;

    @Min(0)
    private Long rollingP95LatencyMs;

    public GatewayHealthUpdateRequestDTO() {}

    public GatewayHealthStatus getStatus() { return status; }
    public void setStatus(GatewayHealthStatus status) { this.status = status; }

    public BigDecimal getRollingSuccessRate() { return rollingSuccessRate; }
    public void setRollingSuccessRate(BigDecimal rollingSuccessRate) { this.rollingSuccessRate = rollingSuccessRate; }

    public Long getRollingP95LatencyMs() { return rollingP95LatencyMs; }
    public void setRollingP95LatencyMs(Long rollingP95LatencyMs) { this.rollingP95LatencyMs = rollingP95LatencyMs; }
}
