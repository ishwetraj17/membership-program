package com.firstclub.payments.routing.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class GatewayRouteRuleUpdateRequestDTO {

    @Positive
    private Integer priority;

    @Size(max = 64)
    private String preferredGateway;

    @Size(max = 64)
    private String fallbackGateway;

    private Boolean active;

    public GatewayRouteRuleUpdateRequestDTO() {}

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getPreferredGateway() { return preferredGateway; }
    public void setPreferredGateway(String preferredGateway) { this.preferredGateway = preferredGateway; }

    public String getFallbackGateway() { return fallbackGateway; }
    public void setFallbackGateway(String fallbackGateway) { this.fallbackGateway = fallbackGateway; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
