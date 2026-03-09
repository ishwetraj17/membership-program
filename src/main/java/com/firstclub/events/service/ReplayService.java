package com.firstclub.events.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.dto.ReplayRequestDTO;
import com.firstclub.events.dto.ReplayResponseDTO;
import com.firstclub.events.dto.ReplayReportDTO;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Re-validates invariants over a time-slice of the immutable domain event log.
 *
 * <p>V29 enhancements:
 * <ul>
 *   <li>Merchant-scoped replay (filter by merchantId).</li>
 *   <li>Aggregate-scoped replay (filter by aggregateType / aggregateId).</li>
 *   <li>Per-type event count breakdown in the response.</li>
 *   <li>REBUILD_PROJECTION mode with a controlled set of supported projections.</li>
 * </ul>
 *
 * <p>Supported projections: {@code subscription_summary}, {@code invoice_ledger}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplayService {

    /** Projections that can be requested via REBUILD_PROJECTION mode. */
    public static final Set<String> SUPPORTED_PROJECTIONS =
            Set.of("subscription_summary", "invoice_ledger");

    private final DomainEventRepository domainEventRepository;
    private final LedgerService         ledgerService;
    private final ObjectMapper          objectMapper;

    // ── V29 rich entry point ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReplayResponseDTO replay(ReplayRequestDTO request) {
        String mode = request.getMode() == null ? "VALIDATE_ONLY" : request.getMode().toUpperCase();

        List<DomainEvent> events = fetchEvents(request);

        List<String>        findings    = new ArrayList<>();
        Map<String, Long>   countByType = countByType(events);
        String              projectionRebuilt = null;

        if ("VALIDATE_ONLY".equals(mode)) {
            findings = validate(events);
        } else if ("REBUILD_PROJECTION".equals(mode)) {
            String name = request.getProjectionName();
            if (!StringUtils.hasText(name) || !SUPPORTED_PROJECTIONS.contains(name)) {
                throw new IllegalArgumentException(
                        "Unsupported projection: '" + name + "'. Supported: " + SUPPORTED_PROJECTIONS);
            }
            // Placeholder: actual rebuild logic would be injected per projection
            log.info("Projection rebuild requested: {} over {} events", name, events.size());
            projectionRebuilt = name;
        } else {
            throw new IllegalArgumentException(
                    "Unknown mode '" + mode + "'. Supported: VALIDATE_ONLY, REBUILD_PROJECTION");
        }

        log.info("Replay {} from={} to={} merchant={} aggregate={}:{} → {} events, {} findings",
                mode, request.getFrom(), request.getTo(),
                request.getMerchantId(), request.getAggregateType(), request.getAggregateId(),
                events.size(), findings.size());

        return ReplayResponseDTO.builder()
                .from(request.getFrom()).to(request.getTo()).mode(mode)
                .merchantId(request.getMerchantId())
                .aggregateType(request.getAggregateType())
                .aggregateId(request.getAggregateId())
                .eventsScanned(events.size())
                .valid(findings.isEmpty())
                .findings(findings)
                .countByType(countByType)
                .projectionRebuilt(projectionRebuilt)
                .build();
    }

    // ── Legacy entry point (backwards-compatible with ReplayController v1) ────

    @Transactional(readOnly = true)
    public ReplayReportDTO replay(LocalDateTime from, LocalDateTime to, String mode) {
        if (!"VALIDATE_ONLY".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Only VALIDATE_ONLY mode is supported");
        }
        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(from).to(to).mode("VALIDATE_ONLY").build();
        ReplayResponseDTO rich = replay(req);
        return ReplayReportDTO.builder()
                .from(rich.getFrom()).to(rich.getTo()).mode(rich.getMode())
                .eventsScanned(rich.getEventsScanned())
                .valid(rich.isValid())
                .findings(rich.getFindings())
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<DomainEvent> fetchEvents(ReplayRequestDTO req) {
        LocalDateTime from = req.getFrom();
        LocalDateTime to   = req.getTo();

        if (StringUtils.hasText(req.getAggregateId()) && StringUtils.hasText(req.getAggregateType())) {
            return domainEventRepository
                    .findByAggregateTypeAndAggregateIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                            req.getAggregateType(), req.getAggregateId(), from, to);
        }
        if (StringUtils.hasText(req.getAggregateType())) {
            return domainEventRepository
                    .findByAggregateTypeAndCreatedAtBetweenOrderByCreatedAtAsc(
                            req.getAggregateType(), from, to);
        }
        if (req.getMerchantId() != null) {
            return domainEventRepository
                    .findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                            req.getMerchantId(), from, to);
        }
        return domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to);
    }

    private List<String> validate(List<DomainEvent> events) {
        List<String> findings = new ArrayList<>();

        Map<String, List<DomainEvent>> byType = events.stream()
                .collect(Collectors.groupingBy(DomainEvent::getEventType));

        // ── Invariant 1: no duplicate invoiceId in INVOICE_CREATED ──
        Map<Long, List<DomainEvent>> invoiceCreatedById = new HashMap<>();
        for (DomainEvent e : byType.getOrDefault(DomainEventTypes.INVOICE_CREATED, List.of())) {
            Long invoiceId = longField(e, "invoiceId");
            if (invoiceId != null) {
                invoiceCreatedById.computeIfAbsent(invoiceId, k -> new ArrayList<>()).add(e);
            }
        }
        invoiceCreatedById.forEach((id, evts) -> {
            if (evts.size() > 1) {
                findings.add("DUPLICATE_INVOICE_CREATED: invoiceId=" + id +
                        " appears " + evts.size() + " times in window");
            }
        });

        // ── Invariant 2: PAYMENT_SUCCEEDED must have matching INVOICE_CREATED ──
        Set<Long> createdInvoiceIds = invoiceCreatedById.keySet();
        for (DomainEvent e : byType.getOrDefault(DomainEventTypes.PAYMENT_SUCCEEDED, List.of())) {
            Long invoiceId = longField(e, "invoiceId");
            if (invoiceId != null && !createdInvoiceIds.contains(invoiceId)) {
                findings.add("ORPHAN_PAYMENT_SUCCEEDED: invoiceId=" + invoiceId +
                        " has PAYMENT_SUCCEEDED but no INVOICE_CREATED in window");
            }
        }

        // ── Invariant 3: SUBSCRIPTION_ACTIVATED must have matching PAYMENT_SUCCEEDED ──
        Set<Long> paidSubscriptionIds = new HashSet<>();
        for (DomainEvent e : byType.getOrDefault(DomainEventTypes.PAYMENT_SUCCEEDED, List.of())) {
            Long subId = longField(e, "subscriptionId");
            if (subId != null) paidSubscriptionIds.add(subId);
        }
        for (DomainEvent e : byType.getOrDefault(DomainEventTypes.SUBSCRIPTION_ACTIVATED, List.of())) {
            Long subId = longField(e, "subscriptionId");
            if (subId != null && !paidSubscriptionIds.contains(subId)) {
                findings.add("ORPHAN_SUBSCRIPTION_ACTIVATED: subscriptionId=" + subId +
                        " was activated without PAYMENT_SUCCEEDED in window");
            }
        }

        // ── Invariant 4: ledger is globally balanced ──
        try {
            var balances = ledgerService.getBalances();
            BigDecimal totalDebit  = balances.stream().map(b -> b.getDebitTotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = balances.stream().map(b -> b.getCreditTotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalDebit.compareTo(totalCredit) != 0) {
                findings.add("LEDGER_UNBALANCED: total debits=" + totalDebit + " total credits=" + totalCredit);
            }
        } catch (Exception ex) {
            findings.add("LEDGER_CHECK_ERROR: " + ex.getMessage());
        }

        return findings;
    }

    private Map<String, Long> countByType(List<DomainEvent> events) {
        return events.stream()
                .collect(Collectors.groupingBy(DomainEvent::getEventType, Collectors.counting()));
    }

    private Long longField(DomainEvent event, String field) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asLong() : null;
        } catch (Exception ex) {
            log.warn("Could not parse field '{}' from event {}: {}", field, event.getId(), ex.getMessage());
            return null;
        }
    }
}
