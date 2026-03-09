package com.firstclub.payments.routing.dto;

import java.time.LocalDateTime;

/**
 * Read-only status snapshot of the gateway routing cache layer returned by the
 * {@code GET /api/v2/admin/gateway-routes/routing-cache} admin endpoint.
 */
public class RoutingCacheStatusDTO {

    private boolean redisEnabled;
    private int gatewayHealthCacheTtlSeconds;
    private int routingRuleCacheTtlSeconds;
    private String keyPattern;
    private String note;
    private LocalDateTime reportedAt;

    public RoutingCacheStatusDTO() {}

    public RoutingCacheStatusDTO(boolean redisEnabled, int gatewayHealthCacheTtlSeconds,
                                  int routingRuleCacheTtlSeconds, String keyPattern,
                                  String note, LocalDateTime reportedAt) {
        this.redisEnabled = redisEnabled;
        this.gatewayHealthCacheTtlSeconds = gatewayHealthCacheTtlSeconds;
        this.routingRuleCacheTtlSeconds = routingRuleCacheTtlSeconds;
        this.keyPattern = keyPattern;
        this.note = note;
        this.reportedAt = reportedAt;
    }

    public boolean isRedisEnabled() { return redisEnabled; }
    public void setRedisEnabled(boolean redisEnabled) { this.redisEnabled = redisEnabled; }

    public int getGatewayHealthCacheTtlSeconds() { return gatewayHealthCacheTtlSeconds; }
    public void setGatewayHealthCacheTtlSeconds(int gatewayHealthCacheTtlSeconds) {
        this.gatewayHealthCacheTtlSeconds = gatewayHealthCacheTtlSeconds;
    }

    public int getRoutingRuleCacheTtlSeconds() { return routingRuleCacheTtlSeconds; }
    public void setRoutingRuleCacheTtlSeconds(int routingRuleCacheTtlSeconds) {
        this.routingRuleCacheTtlSeconds = routingRuleCacheTtlSeconds;
    }

    public String getKeyPattern() { return keyPattern; }
    public void setKeyPattern(String keyPattern) { this.keyPattern = keyPattern; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getReportedAt() { return reportedAt; }
    public void setReportedAt(LocalDateTime reportedAt) { this.reportedAt = reportedAt; }
}
