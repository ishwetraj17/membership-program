package com.firstclub.membership.dto;

import com.firstclub.membership.entity.SubscriptionEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionEventDTO {

    private Long id;
    private Long subscriptionId;
    private Long userId;
    private SubscriptionEvent.EventType eventType;
    private BigDecimal amount;
    private Long planId;
    private String tierName;
    private String paymentReference;
    private LocalDateTime occurredAt;
}
