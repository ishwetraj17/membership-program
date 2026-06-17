package com.firstclub.membership.dto;

import com.firstclub.membership.entity.OutboxEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventDTO {
    private Long id;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private OutboxEvent.Status status;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime dispatchedAt;
}
