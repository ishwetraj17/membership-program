package com.firstclub.payments.routing.dto;

import java.time.LocalDateTime;

/**
 * Immutable audit record capturing every observable fact at the moment a routing decision
 * was made for a payment attempt.
 *
 * <p>Persisted as {@code routing_snapshot_json} (TEXT) on the {@code payment_attempts} table
 * so that post-mortem investigations can reconstruct exactly why a specific gateway was
 * chosen without replaying live data.
 *
 * <h3>Source-of-truth guarantees</h3>
 * <ul>
 *   <li>Health status values ({@link #preferredGatewayStatus}, {@link #fallbackGatewayStatus})
 *       reflect the state observed from the Redis health cache (or DB fallback) at decision time.
 *       They are intentionally not back-filled if the gateway status changes later.</li>
 *   <li>This object is serialised to JSON and stored once; it is never updated in-place.</li>
 * </ul>
 */
public class RoutingDecisionSnapshot {

    /** The gateway actually chosen for this attempt. */
    private String selectedGateway;

    /** The preferred gateway nominated by the matched routing rule. */
    private String preferredGateway;

    /**
     * The fallback gateway nominated by the matched routing rule.
     * {@code null} when the rule has no fallback configured.
     */
    private String fallbackGateway;

    /**
     * {@code true} when the fallback gateway was chosen because the preferred gateway
     * reported a {@code DOWN} status at decision time.
     */
    private boolean fallbackUsed;

    /** Database PK of the {@code GatewayRouteRule} that matched this attempt. */
    private Long ruleId;

    /**
     * {@code true} when the matched rule is merchant-specific (i.e. the rule's
     * {@code merchantId} matches the payment intent's merchant).
     * {@code false} when the platform-wide default rules were used.
     */
    private boolean merchantSpecific;

    /** Payment method type that drove the rule match (e.g. {@code "CARD"}, {@code "UPI"}). */
    private String methodType;

    /** ISO-4217 currency code that drove the rule match (e.g. {@code "INR"}, {@code "USD"}). */
    private String currency;

    /** Attempt sequence number within the parent payment intent (1-based). */
    private int retryNumber;

    /**
     * Health status of the preferred gateway as observed at decision time.
     * One of {@code HEALTHY}, {@code DEGRADED}, or {@code DOWN}.
     */
    private String preferredGatewayStatus;

    /**
     * Health status of the fallback gateway as observed at decision time.
     * {@code null} when no fallback is configured for the matched rule.
     */
    private String fallbackGatewayStatus;

    /** Human-readable explanation of why the selected gateway was chosen. */
    private String reasoningSummary;

    /** Wall-clock timestamp at which this decision was finalised. */
    private LocalDateTime decidedAt;

    public RoutingDecisionSnapshot() {}

    // ── Getters & setters ──────────────────────────────────────────────────

    public String getSelectedGateway() { return selectedGateway; }
    public void setSelectedGateway(String selectedGateway) { this.selectedGateway = selectedGateway; }

    public String getPreferredGateway() { return preferredGateway; }
    public void setPreferredGateway(String preferredGateway) { this.preferredGateway = preferredGateway; }

    public String getFallbackGateway() { return fallbackGateway; }
    public void setFallbackGateway(String fallbackGateway) { this.fallbackGateway = fallbackGateway; }

    public boolean isFallbackUsed() { return fallbackUsed; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public boolean isMerchantSpecific() { return merchantSpecific; }
    public void setMerchantSpecific(boolean merchantSpecific) { this.merchantSpecific = merchantSpecific; }

    public String getMethodType() { return methodType; }
    public void setMethodType(String methodType) { this.methodType = methodType; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getRetryNumber() { return retryNumber; }
    public void setRetryNumber(int retryNumber) { this.retryNumber = retryNumber; }

    public String getPreferredGatewayStatus() { return preferredGatewayStatus; }
    public void setPreferredGatewayStatus(String preferredGatewayStatus) {
        this.preferredGatewayStatus = preferredGatewayStatus;
    }

    public String getFallbackGatewayStatus() { return fallbackGatewayStatus; }
    public void setFallbackGatewayStatus(String fallbackGatewayStatus) {
        this.fallbackGatewayStatus = fallbackGatewayStatus;
    }

    public String getReasoningSummary() { return reasoningSummary; }
    public void setReasoningSummary(String reasoningSummary) { this.reasoningSummary = reasoningSummary; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
}
