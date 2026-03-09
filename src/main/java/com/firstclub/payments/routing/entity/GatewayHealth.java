package com.firstclub.payments.routing.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "gateway_health")
public class GatewayHealth {

    @Id
    @Column(name = "gateway_name", length = 64)
    private String gatewayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GatewayHealthStatus status;

    @Column(name = "last_checked_at", nullable = false)
    private LocalDateTime lastCheckedAt;

    @Column(name = "rolling_success_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal rollingSuccessRate;

    @Column(name = "rolling_p95_latency_ms", nullable = false)
    private Long rollingP95LatencyMs;

    public GatewayHealth() {}

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
