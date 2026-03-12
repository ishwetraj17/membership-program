package com.firstclub.ops;

import com.firstclub.ops.repair.ManualRepairService;
import com.firstclub.ops.timeline.OpsTimelineService;
import com.firstclub.ops.timeline.TimelineEventFactory;
import com.firstclub.platform.ops.dto.DlqEntryResponseDTO;
import com.firstclub.platform.ops.service.DlqOpsService;
import com.firstclub.recon.dto.ReconMismatchDTO;
import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionRegistry;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.platform.repair.RepairAuditService;
import com.firstclub.recon.service.AdvancedReconciliationService;
import com.firstclub.reporting.ops.timeline.dto.TimelineEventDTO;
import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import com.firstclub.reporting.ops.timeline.service.TimelineService;
import com.firstclub.support.dto.SupportNoteCreateRequestDTO;
import com.firstclub.support.dto.SupportNoteResponseDTO;
import com.firstclub.support.entity.SupportCase;
import com.firstclub.support.entity.SupportCaseStatus;
import com.firstclub.support.entity.SupportNote;
import com.firstclub.support.entity.SupportNoteVisibility;
import com.firstclub.support.exception.SupportCaseException;
import com.firstclub.support.repository.SupportCaseRepository;
import com.firstclub.support.repository.SupportNoteRepository;
import com.firstclub.support.service.SupportNoteService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 20 unit tests — Ops timeline, support case notes, and manual repair actions.
 *
 * <h3>Coverage</h3>
 * <ol>
 *   <li>{@link TimelineEventFactory} — event type strings, entity type tagging,
 *       actor annotation in summary</li>
 *   <li>{@link OpsTimelineService} — delegates to {@link TimelineService},
 *       swallows exceptions from {@code appendManual}</li>
 *   <li>{@link ManualRepairService} — registry dispatch, audit recording,
 *       DLQ delegation, mismatch delegation, and timeline side-effect</li>
 *   <li>{@link SupportNoteService} — note creation, CLOSED guard,
 *       full-list retrieval, and visibility-filtered retrieval</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Phase 20 — Ops Timeline, Support Notes & Manual Repair")
class Phase20OpsTimelineTests {

    // =========================================================================
    // TimelineEventFactory
    // =========================================================================

    @Nested
    @DisplayName("TimelineEventFactory — event type tagging")
    class TimelineFactoryTests {

        final TimelineEventFactory factory = new TimelineEventFactory();

        @Test
        @DisplayName("forOutboxRetry produces REPAIR_OUTBOX_RETRY event type")
        void forOutboxRetry_eventType() {
            TimelineEvent event = factory.forOutboxRetry(42L, 1L, "user:9");
            assertThat(event.getEventType()).isEqualTo(TimelineEventFactory.REPAIR_OUTBOX_RETRY);
            assertThat(event.getEntityType()).isEqualTo("OUTBOX_EVENT");
            assertThat(event.getEntityId()).isEqualTo(42L);
            assertThat(event.getMerchantId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("forWebhookRetry produces REPAIR_WEBHOOK_RETRY event type")
        void forWebhookRetry_eventType() {
            TimelineEvent event = factory.forWebhookRetry(55L, 2L, "user:7");
            assertThat(event.getEventType()).isEqualTo(TimelineEventFactory.REPAIR_WEBHOOK_RETRY);
            assertThat(event.getEntityType()).isEqualTo("WEBHOOK_DELIVERY");
            assertThat(event.getEntityId()).isEqualTo(55L);
        }

        @Test
        @DisplayName("forReconRerun uses 0 as entityId (no specific entity)")
        void forReconRerun_zeroEntityId() {
            TimelineEvent event = factory.forReconRerun(LocalDate.of(2025, 6, 1), 1L, null);
            assertThat(event.getEventType()).isEqualTo(TimelineEventFactory.REPAIR_RECON_RERUN);
            assertThat(event.getEntityId()).isEqualTo(0L);
        }

        @Test
        @DisplayName("actor annotation appended when actorContext is non-null")
        void actorAnnotation_appendedToSummary() {
            TimelineEvent event = factory.forOutboxRetry(1L, 1L, "user:42");
            assertThat(event.getSummary()).contains("[actor=user:42]");
        }

        @Test
        @DisplayName("no actor annotation appended when actorContext is null")
        void actorAnnotation_omittedWhenNull() {
            TimelineEvent event = factory.forOutboxRetry(1L, 1L, null);
            assertThat(event.getSummary()).doesNotContain("[actor=");
        }

        @Test
        @DisplayName("forInvoiceRebuild targets INVOICE entity type")
        void forInvoiceRebuild_entityType() {
            TimelineEvent event = factory.forInvoiceRebuild(100L, 1L, "user:3");
            assertThat(event.getEntityType()).isEqualTo("INVOICE");
            assertThat(event.getEntityId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("forDlqRequeue targets DLQ_MESSAGE entity type")
        void forDlqRequeue_entityType() {
            TimelineEvent event = factory.forDlqRequeue(77L, 1L, "user:5");
            assertThat(event.getEntityType()).isEqualTo("DLQ_MESSAGE");
        }

        @Test
        @DisplayName("forMismatchAcknowledge targets RECON_MISMATCH entity type")
        void forMismatchAcknowledge_entityType() {
            TimelineEvent event = factory.forMismatchAcknowledge(88L, 1L, null);
            assertThat(event.getEntityType()).isEqualTo("RECON_MISMATCH");
            assertThat(event.getEntityId()).isEqualTo(88L);
        }

        @Test
        @DisplayName("sourceEventId is null on all factory events (manual entries bypass dedup)")
        void sourceEventId_isNull() {
            TimelineEvent event = factory.forLedgerRebuild(1L, "2025-06-01", "user:1");
            assertThat(event.getSourceEventId()).isNull();
        }
    }

    // =========================================================================
    // OpsTimelineService
    // =========================================================================

    @Nested
    @DisplayName("OpsTimelineService — delegation and fault tolerance")
    class OpsTimelineServiceTests {

        @Mock private TimelineService      timelineService;
        @Mock private TimelineEventFactory factory;
        @InjectMocks private OpsTimelineService opsTimelineService;

        @Test
        @DisplayName("recordOutboxRetry delegates to timelineService.appendManual")
        void recordOutboxRetry_delegatesToAppendManual() {
            TimelineEvent evt = stubEvent(TimelineEventFactory.REPAIR_OUTBOX_RETRY);
            when(factory.forOutboxRetry(1L, 10L, "user:9")).thenReturn(evt);

            opsTimelineService.recordOutboxRetry(1L, 10L, "user:9");

            verify(timelineService).appendManual(evt);
        }

        @Test
        @DisplayName("recordWebhookRetry delegates to timelineService.appendManual")
        void recordWebhookRetry_delegatesToAppendManual() {
            TimelineEvent evt = stubEvent(TimelineEventFactory.REPAIR_WEBHOOK_RETRY);
            when(factory.forWebhookRetry(2L, 10L, null)).thenReturn(evt);

            opsTimelineService.recordWebhookRetry(2L, 10L, null);

            verify(timelineService).appendManual(evt);
        }

        @Test
        @DisplayName("appendSafe swallows exception from timelineService.appendManual")
        void appendSafe_swallowsException() {
            TimelineEvent evt = stubEvent(TimelineEventFactory.REPAIR_DLQ_REQUEUE);
            when(factory.forDlqRequeue(3L, 10L, "user:1")).thenReturn(evt);
            doThrow(new RuntimeException("DB down")).when(timelineService).appendManual(evt);

            // Must NOT throw — timeline is derived data
            assertThatCode(() -> opsTimelineService.recordDlqRequeue(3L, 10L, "user:1"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("getEntityTimeline delegates to timelineService.getTimelineForEntity")
        void getEntityTimeline_delegates() {
            TimelineEventDTO dto = TimelineEventDTO.builder().id(1L).build();
            when(timelineService.getTimelineForEntity(10L, "INVOICE", 20L))
                    .thenReturn(List.of(dto));

            List<TimelineEventDTO> result = opsTimelineService.getEntityTimeline(10L, "INVOICE", 20L);

            assertThat(result).hasSize(1).contains(dto);
        }

        private TimelineEvent stubEvent(String eventType) {
            return TimelineEvent.builder()
                    .merchantId(10L).entityType("OUTBOX_EVENT").entityId(1L)
                    .eventType(eventType).eventTime(LocalDateTime.now())
                    .title("test").summary("summary").build();
        }
    }

    // =========================================================================
    // ManualRepairService
    // =========================================================================

    @Nested
    @DisplayName("ManualRepairService — repair dispatch, audit, and timeline")
    class ManualRepairServiceTests {

        @Mock private RepairActionRegistry          registry;
        @Mock private RepairAuditService            repairAuditService;
        @Mock private OpsTimelineService            opsTimelineService;
        @Mock private DlqOpsService                 dlqOpsService;
        @Mock private AdvancedReconciliationService advancedReconciliationService;

        @InjectMocks private ManualRepairService repairService;

        // ── Outbox retry ───────────────────────────────────────────────────────

        @Test
        @DisplayName("retryOutboxEvent: dispatches to registry, audits, records timeline")
        void retryOutboxEvent_happyPath() {
            RepairActionResult result = successResult("repair.outbox.retry_event");
            RepairAction action = stubAction(result);
            when(registry.findByKey("repair.outbox.retry_event")).thenReturn(Optional.of(action));

            RepairActionResult actual = repairService.retryOutboxEvent(7L, 1L, 9L, "manual");

            assertThat(actual.isSuccess()).isTrue();
            verify(repairAuditService).record(any(), eq(result));
            verify(opsTimelineService).recordOutboxRetry(7L, 1L, "user:9");
        }

        @Test
        @DisplayName("retryOutboxEvent: throws when action not registered")
        void retryOutboxEvent_unknownKey_throws() {
            when(registry.findByKey(anyString())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> repairService.retryOutboxEvent(7L, 1L, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not registered");
        }

        // ── Webhook retry ──────────────────────────────────────────────────────

        @Test
        @DisplayName("retryWebhookDelivery: dispatches to registry, audits, records timeline")
        void retryWebhookDelivery_happyPath() {
            RepairActionResult result = successResult("repair.webhook.retry_delivery");
            RepairAction action = stubAction(result);
            when(registry.findByKey("repair.webhook.retry_delivery")).thenReturn(Optional.of(action));

            RepairActionResult actual = repairService.retryWebhookDelivery(55L, 2L, 8L, "");

            assertThat(actual.isSuccess()).isTrue();
            verify(opsTimelineService).recordWebhookRetry(55L, 2L, "user:8");
        }

        // ── Recon rerun ────────────────────────────────────────────────────────

        @Test
        @DisplayName("rerunReconciliation: passes date param to context, records timeline")
        void rerunReconciliation_happyPath() {
            RepairActionResult result = successResult("repair.recon.run");
            RepairAction action = stubAction(result);
            when(registry.findByKey("repair.recon.run")).thenReturn(Optional.of(action));
            LocalDate date = LocalDate.of(2025, 6, 1);

            repairService.rerunReconciliation(date, 1L, 3L, "reason");

            ArgumentCaptor<RepairAction.RepairContext> ctxCaptor =
                    ArgumentCaptor.forClass(RepairAction.RepairContext.class);
            verify(action).execute(ctxCaptor.capture());
            assertThat(ctxCaptor.getValue().params()).containsEntry("date", "2025-06-01");
            verify(opsTimelineService).recordReconRerun(date, 1L, "user:3");
        }

        // ── Invoice rebuild ────────────────────────────────────────────────────

        @Test
        @DisplayName("rebuildInvoiceTotals: dispatches to registry with invoiceId as targetId")
        void rebuildInvoiceTotals_happyPath() {
            RepairActionResult result = successResult("repair.invoice.recompute_totals");
            RepairAction action = stubAction(result);
            when(registry.findByKey("repair.invoice.recompute_totals")).thenReturn(Optional.of(action));

            repairService.rebuildInvoiceTotals(100L, 1L, 4L, "");

            ArgumentCaptor<RepairAction.RepairContext> ctxCaptor =
                    ArgumentCaptor.forClass(RepairAction.RepairContext.class);
            verify(action).execute(ctxCaptor.capture());
            assertThat(ctxCaptor.getValue().targetId()).isEqualTo("100");
            verify(opsTimelineService).recordInvoiceRebuild(100L, 1L, "user:4");
        }

        // ── Ledger snapshot rebuild ────────────────────────────────────────────

        @Test
        @DisplayName("rebuildLedgerSnapshot: defaults date to today when null")
        void rebuildLedgerSnapshot_nullDateDefaultsToToday() {
            RepairActionResult result = successResult("repair.ledger.rebuild_snapshot");
            RepairAction action = stubAction(result);
            when(registry.findByKey("repair.ledger.rebuild_snapshot")).thenReturn(Optional.of(action));

            repairService.rebuildLedgerSnapshot(null, 1L, 5L, "");

            ArgumentCaptor<RepairAction.RepairContext> ctxCaptor =
                    ArgumentCaptor.forClass(RepairAction.RepairContext.class);
            verify(action).execute(ctxCaptor.capture());
            String dateParam = ctxCaptor.getValue().params().get("date");
            assertThat(dateParam).isEqualTo(LocalDate.now().toString());
        }

        // ── DLQ requeue ────────────────────────────────────────────────────────

        @Test
        @DisplayName("requeueDlqMessage: delegates to dlqOpsService, records timeline")
        void requeueDlqMessage_happyPath() {
            DlqEntryResponseDTO dto = new DlqEntryResponseDTO(
                    9L, "OUTBOX", "SUBSCRIPTION_CREATED|{}", null,
                    LocalDateTime.now(), "SUBSCRIPTION_CREATED", null, 1L);
            when(dlqOpsService.retryDlqEntry(9L)).thenReturn(dto);

            RepairActionResult result = repairService.requeueDlqMessage(9L, 1L, 6L, "");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getDetails()).contains("9");
            verify(opsTimelineService).recordDlqRequeue(9L, 1L, "user:6");
        }

        @Test
        @DisplayName("requeueDlqMessage: returns failure result when service throws")
        void requeueDlqMessage_serviceThrows_returnsFailure() {
            when(dlqOpsService.retryDlqEntry(99L)).thenThrow(new RuntimeException("Not found"));

            RepairActionResult result = repairService.requeueDlqMessage(99L, 1L, null, "");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("Not found");
        }

        // ── Mismatch acknowledge ───────────────────────────────────────────────

        @Test
        @DisplayName("acknowledgeMismatch: delegates to advancedReconService, records timeline")
        void acknowledgeMismatch_happyPath() {
            when(advancedReconciliationService.acknowledgeMismatch(50L, 7L))
                    .thenReturn(mock(ReconMismatchDTO.class));

            RepairActionResult result = repairService.acknowledgeMismatch(50L, 1L, 7L, "");

            assertThat(result.isSuccess()).isTrue();
            verify(advancedReconciliationService).acknowledgeMismatch(50L, 7L);
            verify(opsTimelineService).recordMismatchAcknowledge(50L, 1L, "user:7");
        }

        @Test
        @DisplayName("acknowledgeMismatch: returns failure result when service throws")
        void acknowledgeMismatch_throws_returnsFailure() {
            doThrow(new RuntimeException("Mismatch locked")).when(advancedReconciliationService)
                    .acknowledgeMismatch(eq(50L), any());

            RepairActionResult result = repairService.acknowledgeMismatch(50L, 1L, null, "");

            assertThat(result.isSuccess()).isFalse();
        }

        // ── actorLabel ─────────────────────────────────────────────────────────

        @Test
        @DisplayName("null actorUserId → actorLabel is 'user:X' format OR system")
        void actorLabel_systemWhenNull() {
            RepairActionResult result = successResult("repair.outbox.retry_event");
            RepairAction action = stubAction(result);
            when(registry.findByKey("repair.outbox.retry_event")).thenReturn(Optional.of(action));

            repairService.retryOutboxEvent(7L, 1L, null, null);

            // actor context passed to opsTimeline should be "system"
            verify(opsTimelineService).recordOutboxRetry(7L, 1L, "system");
        }

        // ── helpers ────────────────────────────────────────────────────────────

        private RepairAction stubAction(RepairActionResult result) {
            RepairAction action = mock(RepairAction.class);
            when(action.execute(any())).thenReturn(result);
            return action;
        }

        private RepairActionResult successResult(String key) {
            return RepairActionResult.builder()
                    .repairKey(key).success(true).dryRun(false)
                    .details("ok").evaluatedAt(LocalDateTime.now()).build();
        }
    }

    // =========================================================================
    // SupportNoteService
    // =========================================================================

    @Nested
    @DisplayName("SupportNoteService — note creation and visibility filtering")
    class SupportNoteServiceTests {

        @Mock private SupportNoteRepository noteRepository;
        @Mock private SupportCaseRepository caseRepository;

        @InjectMocks private SupportNoteService noteService;

        private static final Long CASE_ID  = 100L;
        private static final Long NOTE_ID  = 200L;
        private static final Long AUTHOR   = 9L;

        // ── addNote ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("addNote: creates note with INTERNAL_ONLY visibility by default")
        void addNote_defaultVisibility() {
            SupportCase openCase = openCase(CASE_ID, SupportCaseStatus.OPEN);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(openCase));
            when(noteRepository.save(any())).thenAnswer(inv -> {
                SupportNote n = inv.getArgument(0);
                return SupportNote.builder()
                        .id(NOTE_ID).caseId(n.getCaseId()).noteText(n.getNoteText())
                        .authorUserId(n.getAuthorUserId()).visibility(n.getVisibility())
                        .createdAt(LocalDateTime.now()).build();
            });

            SupportNoteCreateRequestDTO req = SupportNoteCreateRequestDTO.builder()
                    .noteText("Investigating payment failure")
                    .authorUserId(AUTHOR)
                    .visibility(null)  // not set → should default to INTERNAL_ONLY
                    .build();

            SupportNoteResponseDTO response = noteService.addNote(CASE_ID, req);

            assertThat(response.getVisibility()).isEqualTo(SupportNoteVisibility.INTERNAL_ONLY);
            assertThat(response.getNoteText()).isEqualTo("Investigating payment failure");
            assertThat(response.getCaseId()).isEqualTo(CASE_ID);
        }

        @Test
        @DisplayName("addNote: respects explicit MERCHANT_VISIBLE visibility")
        void addNote_merchantVisibleVisibility() {
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(openCase(CASE_ID, SupportCaseStatus.IN_PROGRESS)));
            when(noteRepository.save(any())).thenAnswer(inv -> {
                SupportNote n = inv.getArgument(0);
                return SupportNote.builder()
                        .id(NOTE_ID).caseId(n.getCaseId()).noteText(n.getNoteText())
                        .authorUserId(n.getAuthorUserId()).visibility(n.getVisibility())
                        .createdAt(LocalDateTime.now()).build();
            });

            SupportNoteCreateRequestDTO req = SupportNoteCreateRequestDTO.builder()
                    .noteText("Your refund is being processed.")
                    .authorUserId(AUTHOR)
                    .visibility(SupportNoteVisibility.MERCHANT_VISIBLE)
                    .build();

            SupportNoteResponseDTO response = noteService.addNote(CASE_ID, req);

            assertThat(response.getVisibility()).isEqualTo(SupportNoteVisibility.MERCHANT_VISIBLE);
        }

        @Test
        @DisplayName("addNote: throws CONFLICT when case is CLOSED")
        void addNote_closedCase_throwsConflict() {
            when(caseRepository.findById(CASE_ID))
                    .thenReturn(Optional.of(openCase(CASE_ID, SupportCaseStatus.CLOSED)));

            SupportNoteCreateRequestDTO req = SupportNoteCreateRequestDTO.builder()
                    .noteText("Reopening note").authorUserId(AUTHOR).build();

            assertThatThrownBy(() -> noteService.addNote(CASE_ID, req))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex -> {
                        SupportCaseException sce = (SupportCaseException) ex;
                        assertThat(sce.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(sce.getErrorCode()).isEqualTo("SUPPORT_CASE_ALREADY_CLOSED");
                    });
        }

        @Test
        @DisplayName("addNote: throws NOT_FOUND when case does not exist")
        void addNote_notFound_throws() {
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.empty());

            SupportNoteCreateRequestDTO req = SupportNoteCreateRequestDTO.builder()
                    .noteText("Test").authorUserId(AUTHOR).build();

            assertThatThrownBy(() -> noteService.addNote(CASE_ID, req))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex -> assertThat(((SupportCaseException) ex).getHttpStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        // ── listNotes ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("listNotes: returns all notes ordered oldest first")
        void listNotes_allNotes() {
            List<SupportNote> notes = List.of(
                    note(201L, CASE_ID, "First", SupportNoteVisibility.INTERNAL_ONLY),
                    note(202L, CASE_ID, "Second", SupportNoteVisibility.MERCHANT_VISIBLE));
            when(noteRepository.findByCaseIdOrderByCreatedAtAsc(CASE_ID)).thenReturn(notes);

            List<SupportNoteResponseDTO> result = noteService.listNotes(CASE_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getNoteText()).isEqualTo("First");
            assertThat(result.get(1).getNoteText()).isEqualTo("Second");
        }

        // ── listVisibleNotes ───────────────────────────────────────────────────

        @Test
        @DisplayName("listVisibleNotes(MERCHANT_VISIBLE): filters out INTERNAL_ONLY notes")
        void listVisibleNotes_filtersCorrectly() {
            List<SupportNote> notes = List.of(
                    note(201L, CASE_ID, "Internal only",  SupportNoteVisibility.INTERNAL_ONLY),
                    note(202L, CASE_ID, "Visible note",   SupportNoteVisibility.MERCHANT_VISIBLE),
                    note(203L, CASE_ID, "Also internal",  SupportNoteVisibility.INTERNAL_ONLY));
            when(noteRepository.findByCaseIdOrderByCreatedAtAsc(CASE_ID)).thenReturn(notes);

            List<SupportNoteResponseDTO> result =
                    noteService.listVisibleNotes(CASE_ID, SupportNoteVisibility.MERCHANT_VISIBLE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNoteText()).isEqualTo("Visible note");
        }

        @Test
        @DisplayName("listVisibleNotes(INTERNAL_ONLY): returns only internal notes")
        void listVisibleNotes_internalOnly() {
            List<SupportNote> notes = List.of(
                    note(201L, CASE_ID, "Internal",  SupportNoteVisibility.INTERNAL_ONLY),
                    note(202L, CASE_ID, "Merchant",  SupportNoteVisibility.MERCHANT_VISIBLE));
            when(noteRepository.findByCaseIdOrderByCreatedAtAsc(CASE_ID)).thenReturn(notes);

            List<SupportNoteResponseDTO> result =
                    noteService.listVisibleNotes(CASE_ID, SupportNoteVisibility.INTERNAL_ONLY);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getVisibility()).isEqualTo(SupportNoteVisibility.INTERNAL_ONLY);
        }

        // ── helpers ────────────────────────────────────────────────────────────

        private SupportCase openCase(Long id, SupportCaseStatus status) {
            return SupportCase.builder()
                    .id(id).merchantId(1L).status(status)
                    .title("Test case").build();
        }

        private SupportNote note(Long id, Long caseId, String text, SupportNoteVisibility visibility) {
            return SupportNote.builder()
                    .id(id).caseId(caseId).noteText(text)
                    .authorUserId(AUTHOR).visibility(visibility)
                    .createdAt(LocalDateTime.now()).build();
        }
    }
}
