package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Strongly-typed response DTO for the system health endpoint.
 * Replaces the raw Map&lt;String, Object&gt; return type so Swagger
 * can generate a proper schema and consumers get compile-time safety.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemHealthDTO {

    private String status;
    private LocalDateTime timestamp;
    private String version;
    private MetricsDTO metrics;
    private SystemInfoDTO system;
    private String error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsDTO {
        private long totalUsers;
        private long activeSubscriptions;
        private long expiredSubscriptions;
        private long cancelledSubscriptions;
        private int availablePlans;
        private int membershipTiers;
        private Map<String, Long> tierDistribution;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemInfoDTO {
        private String javaVersion;
        private String database;
        private String environment;
    }
}
