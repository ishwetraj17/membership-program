package com.firstclub.payments.routing.dto;

import com.firstclub.payments.routing.entity.GatewayHealthStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class GatewayHealthResponseDTO {

    private String gatewayName;
    private GatewayHealthStatus status;
    private LocalDateTime lastCheckedAt;
    private BigDecimal rollingSuccessRate;
    private Long rollingP95LatencyMs;

    public GatewayHealthResponseDTO() {}

    public String getGatewayName() { return gatewayName; }
    public void setGatewayName(String gatewayName) { this.gatewayName = gatewayName; }

    public GatewayHealthStatus getStatus() { return status; }
    public void setStatus(GatewayHealthStatus status) { this.status = status; }

    public LocalDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(LocalDateTime lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public BigDecimal getRollingSuccessRate() { return rollingSuccessRate; }
    public void setRollingSuccessRate(BigDecimal rollingSuccessRate) { this.rollingSuccessRate = rollingSuccessRate; }

    public Long getRollingP95LatencyMs() { return rollingP95LatencyMs; }
    public void setRollingP95LatencyMs(Long rollingP95LatencyMs) { this.rollingP95LatencyMs = rollingP95LatencyMs; }
}
