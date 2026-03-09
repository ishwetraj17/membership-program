package com.firstclub.events.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.dto.ReplayRequestDTO;
import com.firstclub.events.dto.ReplayReportDTO;
import com.firstclub.events.dto.ReplayResponseDTO;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.ledger.dto.LedgerAccountBalanceDTO;
import com.firstclub.ledger.service.LedgerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReplayService}.
 *
 * <p>Uses Mockito only — no Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReplayService — Unit Tests")
class ReplayServiceTest {

    @Mock
    private DomainEventRepository domainEventRepository;

    @Mock
    private LedgerService ledgerService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ReplayService replayService;

    private static final LocalDateTime FROM = LocalDateTime.of(2024, 1, 1, 0, 0);
    private static final LocalDateTime TO   = LocalDateTime.of(2024, 1, 31, 23, 59);

    /** Stub a balanced ledger (no LEDGER_UNBALANCED finding). */
    private void stubBalancedLedger() {
        LedgerAccountBalanceDTO b = LedgerAccountBalanceDTO.builder()
                .debitTotal(new BigDecimal("100.00"))
                .creditTotal(new BigDecimal("100.00"))
                .balance(BigDecimal.ZERO)
                .build();
        when(ledgerService.getBalances()).thenReturn(List.of(b));
    }

    // ── Scoped fetch dispatching ─────────────────────────────────────────────

    @Test
    @DisplayName("replay — merchantId only → uses merchant-scoped repo method")
    void replay_merchantScoped_usesCorrectRepo() {
        stubBalancedLedger();
        when(domainEventRepository.findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAsc(5L, FROM, TO))
                .thenReturn(List.of());

        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO).merchantId(5L).mode("VALIDATE_ONLY").build();
        replayService.replay(req);

        verify(domainEventRepository).findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAsc(5L, FROM, TO);
        verify(domainEventRepository, never()).findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any());
    }

    @Test
    @DisplayName("replay — aggregateType only → uses aggregate-type-scoped repo method")
    void replay_aggregateTypeScoped_usesCorrectRepo() {
        stubBalancedLedger();
        when(domainEventRepository.findByAggregateTypeAndCreatedAtBetweenOrderByCreatedAtAsc(
                "Subscription", FROM, TO)).thenReturn(List.of());

        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO).aggregateType("Subscription").mode("VALIDATE_ONLY").build();
        replayService.replay(req);

        verify(domainEventRepository).findByAggregateTypeAndCreatedAtBetweenOrderByCreatedAtAsc("Subscription", FROM, TO);
    }

    @Test
    @DisplayName("replay — aggregateType + aggregateId → uses full aggregate-scoped repo method")
    void replay_aggregateTypeAndIdScoped_usesCorrectRepo() {
        stubBalancedLedger();
        when(domainEventRepository
                .findByAggregateTypeAndAggregateIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                        "Subscription", "sub-42", FROM, TO)).thenReturn(List.of());

        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO)
                .aggregateType("Subscription").aggregateId("sub-42")
                .mode("VALIDATE_ONLY").build();
        replayService.replay(req);

        verify(domainEventRepository)
                .findByAggregateTypeAndAggregateIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                        "Subscription", "sub-42", FROM, TO);
    }

    @Test
    @DisplayName("replay — no scope filters → uses date-range repo method")
    void replay_unscoped_usesDateRange() {
        stubBalancedLedger();
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of());

        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO).mode("VALIDATE_ONLY").build();
        replayService.replay(req);

        verify(domainEventRepository).findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO);
    }

    // ── Invariant validation ─────────────────────────────────────────────────

    @Test
    @DisplayName("replay — duplicate INVOICE_CREATED invoice → finding raised")
    void replay_validateOnly_duplicateInvoice_findingRaised() {
        stubBalancedLedger();
        DomainEvent e1 = eventWithPayload(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":1}");
        DomainEvent e2 = eventWithPayload(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":1}");
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of(e1, e2));

        ReplayResponseDTO result = replayService.replay(
                ReplayRequestDTO.builder().from(FROM).to(TO).mode("VALIDATE_ONLY").build());

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFindings()).anyMatch(f -> f.contains("DUPLICATE_INVOICE_CREATED"));
    }

    @Test
    @DisplayName("replay — PAYMENT_SUCCEEDED with no matching INVOICE_CREATED → orphan finding")
    void replay_validateOnly_orphanPayment_findingRaised() {
        stubBalancedLedger();
        DomainEvent payment = eventWithPayload(DomainEventTypes.PAYMENT_SUCCEEDED, "{\"invoiceId\":99}");
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of(payment));

        ReplayResponseDTO result = replayService.replay(
                ReplayRequestDTO.builder().from(FROM).to(TO).mode("VALIDATE_ONLY").build());

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFindings()).anyMatch(f -> f.contains("ORPHAN_PAYMENT_SUCCEEDED"));
    }

    @Test
    @DisplayName("replay — clean event window with balanced ledger → valid=true, no findings")
    void replay_validateOnly_clean_validTrue() {
        stubBalancedLedger();
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of());

        ReplayResponseDTO result = replayService.replay(
                ReplayRequestDTO.builder().from(FROM).to(TO).mode("VALIDATE_ONLY").build());

        assertThat(result.isValid()).isTrue();
        assertThat(result.getFindings()).isEmpty();
    }

    // ── countByType ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("replay — response includes correct per-type event counts")
    void replay_countByType_correctCounts() {
        stubBalancedLedger();
        List<DomainEvent> events = List.of(
                eventWithPayload(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":10}"),
                eventWithPayload(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":11}"),
                eventWithPayload(DomainEventTypes.PAYMENT_SUCCEEDED, "{\"invoiceId\":10}")
        );
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(events);

        ReplayResponseDTO result = replayService.replay(
                ReplayRequestDTO.builder().from(FROM).to(TO).mode("VALIDATE_ONLY").build());

        Map<String, Long> counts = result.getCountByType();
        assertThat(counts.get(DomainEventTypes.INVOICE_CREATED)).isEqualTo(2L);
        assertThat(counts.get(DomainEventTypes.PAYMENT_SUCCEEDED)).isEqualTo(1L);
        assertThat(result.getEventsScanned()).isEqualTo(3);
    }

    // ── REBUILD_PROJECTION mode ──────────────────────────────────────────────

    @Test
    @DisplayName("replay — REBUILD_PROJECTION with supported projection → projectionRebuilt populated")
    void replay_rebuildProjection_supported_success() {
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of());

        ReplayResponseDTO result = replayService.replay(ReplayRequestDTO.builder()
                .from(FROM).to(TO)
                .mode("REBUILD_PROJECTION")
                .projectionName("subscription_summary")
                .build());

        assertThat(result.getProjectionRebuilt()).isEqualTo("subscription_summary");
    }

    @Test
    @DisplayName("replay — REBUILD_PROJECTION with unsupported name → IllegalArgumentException")
    void replay_rebuildProjection_unsupported_throws() {
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of());

        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO)
                .mode("REBUILD_PROJECTION")
                .projectionName("nonexistent_projection")
                .build();

        assertThatThrownBy(() -> replayService.replay(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported projection");
    }

    @Test
    @DisplayName("replay — unknown mode → IllegalArgumentException")
    void replay_unknownMode_throws() {
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of());

        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO).mode("UNKNOWN_MODE").build();

        assertThatThrownBy(() -> replayService.replay(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown mode");
    }

    // ── Legacy API ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("legacy replay(from, to, VALIDATE_ONLY) — delegates and returns ReplayReportDTO")
    void legacyReplay_delegatesToRichMethod() {
        stubBalancedLedger();
        when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(FROM, TO))
                .thenReturn(List.of());

        ReplayReportDTO report = replayService.replay(FROM, TO, "VALIDATE_ONLY");

        assertThat(report.isValid()).isTrue();
        assertThat(report.getEventsScanned()).isEqualTo(0);
        assertThat(report.getMode()).isEqualTo("VALIDATE_ONLY");
    }

    @Test
    @DisplayName("legacy replay(from, to, NON_VALIDATE) — throws IllegalArgumentException")
    void legacyReplay_nonValidateMode_throws() {
        assertThatThrownBy(() -> replayService.replay(FROM, TO, "REBUILD_PROJECTION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VALIDATE_ONLY");
    }

    // ── SUPPORTED_PROJECTIONS constant ───────────────────────────────────────

    @Test
    @DisplayName("SUPPORTED_PROJECTIONS contains exactly subscription_summary and invoice_ledger")
    void supportedProjections_containsExpectedValues() {
        assertThat(ReplayService.SUPPORTED_PROJECTIONS)
                .containsExactlyInAnyOrder("subscription_summary", "invoice_ledger");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DomainEvent eventWithPayload(String eventType, String json) {
        return DomainEvent.builder()
                .eventType(eventType)
                .payload(json)
                .build();
    }
}
