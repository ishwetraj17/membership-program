package com.firstclub.payments.routing.dto;

public class RoutingDecisionDTO {

    private String selectedGateway;
    private Long ruleId;
    private boolean isFallback;
    private String reason;
    /**
     * JSON-serialised {@link RoutingDecisionSnapshot} captured at decision time.
     * Intended to be persisted on {@code payment_attempts.routing_snapshot_json}.
     * {@code null} when Redis / routing snapshot capture is unavailable.
     */
    private String snapshotJson;

    public RoutingDecisionDTO() {}

    public RoutingDecisionDTO(String selectedGateway, Long ruleId, boolean isFallback, String reason) {
        this.selectedGateway = selectedGateway;
        this.ruleId = ruleId;
        this.isFallback = isFallback;
        this.reason = reason;
    }

    public RoutingDecisionDTO(String selectedGateway, Long ruleId, boolean isFallback,
                               String reason, String snapshotJson) {
        this.selectedGateway = selectedGateway;
        this.ruleId = ruleId;
        this.isFallback = isFallback;
        this.reason = reason;
        this.snapshotJson = snapshotJson;
    }

    public String getSelectedGateway() { return selectedGateway; }
    public void setSelectedGateway(String selectedGateway) { this.selectedGateway = selectedGateway; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public boolean isFallback() { return isFallback; }
    public void setFallback(boolean fallback) { isFallback = fallback; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
}

