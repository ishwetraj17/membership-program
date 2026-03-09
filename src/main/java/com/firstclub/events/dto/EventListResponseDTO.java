package com.firstclub.events.dto;

import com.firstclub.events.entity.DomainEvent;
import lombok.*;

import java.time.LocalDateTime;

/** Response DTO for a single domain event row. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventListResponseDTO {

    private Long   id;
    private String eventType;
    private String payload;

    private int    eventVersion;
    private int    schemaVersion;
    private String correlationId;
    private String causationId;
    private String aggregateType;
    private String aggregateId;
    private Long   merchantId;

    private LocalDateTime createdAt;

    /** Map from a {@link DomainEvent} entity to this DTO. */
    public static EventListResponseDTO from(DomainEvent e) {
        return EventListResponseDTO.builder()
                .id(e.getId())
                .eventType(e.getEventType())
                .payload(e.getPayload())
                .eventVersion(e.getEventVersion())
                .schemaVersion(e.getSchemaVersion())
                .correlationId(e.getCorrelationId())
                .causationId(e.getCausationId())
                .aggregateType(e.getAggregateType())
                .aggregateId(e.getAggregateId())
                .merchantId(e.getMerchantId())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
