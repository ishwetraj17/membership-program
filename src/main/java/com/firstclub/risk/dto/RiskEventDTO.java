package com.firstclub.risk.dto;

import com.firstclub.risk.entity.RiskEvent;

import java.time.LocalDateTime;

/**
 * Read-only projection of a {@link RiskEvent} for the admin API.
 */
public record RiskEventDTO(
        Long id,
        RiskEvent.RiskEventType type,
        RiskEvent.RiskSeverity severity,
        Long userId,
        String ip,
        String deviceId,
        String metadata,
        LocalDateTime createdAt
) {
    public static RiskEventDTO from(RiskEvent e) {
        return new RiskEventDTO(
                e.getId(),
                e.getType(),
                e.getSeverity(),
                e.getUserId(),
                e.getIp(),
                e.getDeviceId(),
                e.getMetadata(),
                e.getCreatedAt()
        );
    }
}
