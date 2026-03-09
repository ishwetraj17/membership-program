package com.firstclub.reporting.ops.timeline.dto;

import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Read-only projection of a {@link TimelineEvent} returned by the timeline API.
 */
@Value
@Builder
public class TimelineEventDTO {

    Long          id;
    Long          merchantId;
    String        entityType;
    Long          entityId;
    String        eventType;
    LocalDateTime eventTime;
    String        title;
    String        summary;
    String        relatedEntityType;
    Long          relatedEntityId;
    String        correlationId;
    String        causationId;
    String        payloadPreviewJson;
    Long          sourceEventId;
    LocalDateTime createdAt;

    public static TimelineEventDTO from(TimelineEvent e) {
        return TimelineEventDTO.builder()
                .id(e.getId())
                .merchantId(e.getMerchantId())
                .entityType(e.getEntityType())
                .entityId(e.getEntityId())
                .eventType(e.getEventType())
                .eventTime(e.getEventTime())
                .title(e.getTitle())
                .summary(e.getSummary())
                .relatedEntityType(e.getRelatedEntityType())
                .relatedEntityId(e.getRelatedEntityId())
                .correlationId(e.getCorrelationId())
                .causationId(e.getCausationId())
                .payloadPreviewJson(e.getPayloadPreviewJson())
                .sourceEventId(e.getSourceEventId())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
