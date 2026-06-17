package com.firstclub.membership.controller;

import com.firstclub.membership.dto.OutboxEventDTO;
import com.firstclub.membership.event.OutboxEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin operations on the transactional outbox dead-letter queue.
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
@RequiredArgsConstructor
@Tag(name = "Admin — Outbox", description = "Inspect and replay dead-letter events")
public class AdminOutboxController {

    private final OutboxEventService outboxEventService;

    @GetMapping("/dead")
    @Operation(summary = "List dead-letter outbox events (admin)")
    public ResponseEntity<List<OutboxEventDTO>> dead() {
        List<OutboxEventDTO> dead = outboxEventService.deadLetters().stream()
                .map(e -> OutboxEventDTO.builder()
                        .id(e.getId()).aggregateType(e.getAggregateType()).aggregateId(e.getAggregateId())
                        .eventType(e.getEventType()).status(e.getStatus()).retryCount(e.getRetryCount())
                        .createdAt(e.getCreatedAt()).dispatchedAt(e.getDispatchedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(dead);
    }

    @PostMapping("/replay")
    @Operation(summary = "Requeue all dead-letter events for redelivery (admin)")
    public ResponseEntity<Map<String, Integer>> replay() {
        return ResponseEntity.ok(Map.of("replayed", outboxEventService.replayDead()));
    }
}
