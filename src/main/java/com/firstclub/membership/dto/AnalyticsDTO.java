package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Strongly-typed response DTO for the analytics endpoint.
 * Replaces Map&lt;String, Object&gt; to give Swagger a proper schema
 * and prevent accidental key typos on the producer side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsDTO {

    private RevenueDTO revenue;
    private MembershipMetricsDTO membership;
    private SummaryDTO summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevenueDTO {
        private BigDecimal totalRevenue;
        private String currency;
        private BigDecimal averageRevenuePerUser;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MembershipMetricsDTO {
        private Map<String, Long> tierPopularity;
        private Map<String, Long> planTypeDistribution;
        private int totalActivePlans;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryDTO {
        private long totalSubscriptions;
        private long activeSubscriptions;
        private LocalDateTime generatedAt;
    }
}
