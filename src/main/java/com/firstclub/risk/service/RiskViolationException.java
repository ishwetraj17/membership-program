package com.firstclub.risk.service;

import com.firstclub.risk.entity.RiskEvent.RiskEventType;
import lombok.Getter;

/**
 * Thrown by {@link RiskService} when a payment attempt is blocked.
 * The {@link #getType()} dictates the HTTP response code:
 * <ul>
 *   <li>{@link RiskEventType#IP_BLOCKED} → 403 Forbidden
 *   <li>{@link RiskEventType#VELOCITY_EXCEEDED} → 429 Too Many Requests
 * </ul>
 */
@Getter
public class RiskViolationException extends RuntimeException {

    private final RiskEventType type;

    public RiskViolationException(RiskEventType type, String message) {
        super(message);
        this.type = type;
    }
}
