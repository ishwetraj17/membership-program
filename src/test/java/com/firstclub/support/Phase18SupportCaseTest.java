package com.firstclub.support;

import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.reporting.ops.timeline.entity.TimelineEntityTypes;
import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import com.firstclub.reporting.ops.timeline.service.TimelineService;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import com.firstclub.support.dto.*;
import com.firstclub.support.entity.*;
import com.firstclub.support.exception.SupportCaseException;
import com.firstclub.support.repository.SupportCaseRepository;
import com.firstclub.support.repository.SupportNoteRepository;
import com.firstclub.support.service.impl.SupportCaseServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 18: Support / Ops Case Tracking — unit-test coverage for:
 * <ul>
 *   <li>Create case with linked-entity validation</li>
 *   <li>Add note (including guard on CLOSED cases)</li>
 *   <li>Assign case (OPEN → IN_PROGRESS transition)</li>
 *   <li>Close case (including double-close guard)</li>
 *   <li>Timeline event written for each state change</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Phase 18: Support Case Tracking")
class Phase18SupportCaseTest {

    // ── mocks wired into SupportCaseServiceImpl via @InjectMocks ────────────
    @Mock SupportCaseRepository     supportCaseRepository;
    @Mock SupportNoteRepository     supportNoteRepository;
    @Mock TimelineService           timelineService;
    @Mock CustomerRepository        customerRepository;
    @Mock SubscriptionV2Repository  subscriptionV2Repository;
    @Mock InvoiceRepository         invoiceRepository;
    @Mock PaymentIntentV2Repository paymentIntentV2Repository;
    @Mock RefundV2Repository        refundV2Repository;
    @Mock DisputeRepository         disputeRepository;
    @Mock ReconMismatchRepository   reconMismatchRepository;

    @InjectMocks
    SupportCaseServiceImpl service;

    // ── test constants ───────────────────────────────────────────────────────
    private static final Long MERCHANT_ID  = 1L;
    private static final Long CUSTOMER_ID  = 10L;
    private static final Long INVOICE_ID   = 20L;
    private static final Long DISPUTE_ID   = 30L;
    private static final Long CASE_ID      = 100L;
    private static final Long NOTE_ID      = 200L;
    private static final Long AUTHOR_ID    = 9L;
    private static final Long OWNER_ID     = 8L;
    private static final String TITLE      = "Payment failure investigation";

    // ── helpers ──────────────────────────────────────────────────────────────

    private SupportCase openCase() {
        return SupportCase.builder()
                .id(CASE_ID)
                .merchantId(MERCHANT_ID)
                .linkedEntityType(TimelineEntityTypes.CUSTOMER)
                .linkedEntityId(CUSTOMER_ID)
                .title(TITLE)
                .status(SupportCaseStatus.OPEN)
                .priority(SupportCasePriority.MEDIUM)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private SupportCase closedCase() {
        SupportCase sc = openCase();
        sc.setStatus(SupportCaseStatus.CLOSED);
        return sc;
    }

    private SupportNote sampleNote(Long caseId) {
        return SupportNote.builder()
                .id(NOTE_ID)
                .caseId(caseId)
                .noteText("Root cause found")
                .authorUserId(AUTHOR_ID)
                .visibility(SupportNoteVisibility.INTERNAL_ONLY)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. Create Linked Case
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. CreateLinkedCase")
    class CreateLinkedCase {

        @Test
        @DisplayName("happy path — customer entity, defaults to MEDIUM priority")
        void createCaseLinkedToCustomer() {
            when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(true);
            SupportCase saved = openCase();
            when(supportCaseRepository.save(any())).thenReturn(saved);

            SupportCaseCreateRequestDTO req = SupportCaseCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.CUSTOMER)
                    .linkedEntityId(CUSTOMER_ID)
                    .title(TITLE)
                    .build();

            SupportCaseResponseDTO dto = service.createCase(req);

            assertThat(dto.getId()).isEqualTo(CASE_ID);
            assertThat(dto.getStatus()).isEqualTo(SupportCaseStatus.OPEN);
            assertThat(dto.getPriority()).isEqualTo(SupportCasePriority.MEDIUM);
            assertThat(dto.getLinkedEntityType()).isEqualTo(TimelineEntityTypes.CUSTOMER);

            verify(supportCaseRepository).save(any(SupportCase.class));
            verify(timelineService).appendManual(any(TimelineEvent.class));
        }

        @Test
        @DisplayName("happy path — invoice entity with CRITICAL priority")
        void createCaseLinkedToInvoice_criticalPriority() {
            when(invoiceRepository.existsById(INVOICE_ID)).thenReturn(true);
            SupportCase saved = SupportCase.builder()
                    .id(CASE_ID).merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.INVOICE)
                    .linkedEntityId(INVOICE_ID).title(TITLE)
                    .status(SupportCaseStatus.OPEN).priority(SupportCasePriority.CRITICAL)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            when(supportCaseRepository.save(any())).thenReturn(saved);

            SupportCaseCreateRequestDTO req = SupportCaseCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.INVOICE)
                    .linkedEntityId(INVOICE_ID)
                    .title(TITLE)
                    .priority(SupportCasePriority.CRITICAL)
                    .build();

            SupportCaseResponseDTO dto = service.createCase(req);

            assertThat(dto.getPriority()).isEqualTo(SupportCasePriority.CRITICAL);
        }

        @Test
        @DisplayName("linked entity not found → HTTP 422")
        void createCase_linkedEntityMissing_throws422() {
            when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(false);

            SupportCaseCreateRequestDTO req = SupportCaseCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.CUSTOMER)
                    .linkedEntityId(CUSTOMER_ID)
                    .title(TITLE)
                    .build();

            assertThatThrownBy(() -> service.createCase(req))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex -> {
                        SupportCaseException sce = (SupportCaseException) ex;
                        assertThat(sce.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(sce.getErrorCode()).isEqualTo("SUPPORT_CASE_LINKED_ENTITY_NOT_FOUND");
                    });

            verify(supportCaseRepository, never()).save(any());
            verify(timelineService, never()).appendManual(any());
        }

        @Test
        @DisplayName("unknown entity type → HTTP 400")
        void createCase_unknownEntityType_throws400() {
            SupportCaseCreateRequestDTO req = SupportCaseCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID)
                    .linkedEntityType("UNKNOWN_TYPE")
                    .linkedEntityId(99L)
                    .title(TITLE)
                    .build();

            assertThatThrownBy(() -> service.createCase(req))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex -> {
                        SupportCaseException sce = (SupportCaseException) ex;
                        assertThat(sce.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(sce.getErrorCode()).isEqualTo("SUPPORT_CASE_UNKNOWN_ENTITY_TYPE");
                    });
        }

        @Test
        @DisplayName("dispute entity type validated correctly")
        void createCase_dispute_entity() {
            when(disputeRepository.existsById(DISPUTE_ID)).thenReturn(true);
            SupportCase saved = SupportCase.builder()
                    .id(CASE_ID).merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.DISPUTE)
                    .linkedEntityId(DISPUTE_ID).title(TITLE)
                    .status(SupportCaseStatus.OPEN).priority(SupportCasePriority.HIGH)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            when(supportCaseRepository.save(any())).thenReturn(saved);

            SupportCaseCreateRequestDTO req = SupportCaseCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.DISPUTE)
                    .linkedEntityId(DISPUTE_ID)
                    .title(TITLE)
                    .priority(SupportCasePriority.HIGH)
                    .build();

            SupportCaseResponseDTO dto = service.createCase(req);
            assertThat(dto.getLinkedEntityType()).isEqualTo(TimelineEntityTypes.DISPUTE);
            assertThat(dto.getPriority()).isEqualTo(SupportCasePriority.HIGH);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. Add Note
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. AddNote")
    class AddNote {

        @Test
        @DisplayName("happy path — note added to open case")
        void addNote_openCase_success() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(openCase()));
            SupportNote saved = sampleNote(CASE_ID);
            when(supportNoteRepository.save(any())).thenReturn(saved);

            SupportNoteCreateRequestDTO req = SupportNoteCreateRequestDTO.builder()
                    .noteText("Root cause found")
                    .authorUserId(AUTHOR_ID)
                    .visibility(SupportNoteVisibility.INTERNAL_ONLY)
                    .build();

            SupportNoteResponseDTO dto = service.addNote(CASE_ID, req);

            assertThat(dto.getId()).isEqualTo(NOTE_ID);
            assertThat(dto.getCaseId()).isEqualTo(CASE_ID);
            assertThat(dto.getAuthorUserId()).isEqualTo(AUTHOR_ID);
            assertThat(dto.getVisibility()).isEqualTo(SupportNoteVisibility.INTERNAL_ONLY);

            verify(supportNoteRepository).save(any(SupportNote.class));
            verify(timelineService).appendManual(any(TimelineEvent.class));
        }

        @Test
        @DisplayName("note defaults to INTERNAL_ONLY when visibility not supplied")
        void addNote_nullVisibility_defaultsToInternalOnly() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(openCase()));
            ArgumentCaptor<SupportNote> captor = ArgumentCaptor.forClass(SupportNote.class);
            when(supportNoteRepository.save(captor.capture())).thenAnswer(inv -> {
                SupportNote n = inv.getArgument(0);
                n = SupportNote.builder().id(NOTE_ID).caseId(n.getCaseId())
                        .noteText(n.getNoteText()).authorUserId(n.getAuthorUserId())
                        .visibility(n.getVisibility()).createdAt(LocalDateTime.now()).build();
                return n;
            });

            SupportNoteCreateRequestDTO req = SupportNoteCreateRequestDTO.builder()
                    .noteText("Quick note")
                    .authorUserId(AUTHOR_ID)
                    // visibility intentionally omitted
                    .build();

            SupportNoteResponseDTO dto = service.addNote(CASE_ID, req);
            assertThat(dto.getVisibility()).isEqualTo(SupportNoteVisibility.INTERNAL_ONLY);
        }

        @Test
        @DisplayName("closed case rejects note → HTTP 409")
        void addNote_closedCase_throws409() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(closedCase()));

            SupportNoteCreateRequestDTO req = SupportNoteCreateRequestDTO.builder()
                    .noteText("Late note")
                    .authorUserId(AUTHOR_ID)
                    .build();

            assertThatThrownBy(() -> service.addNote(CASE_ID, req))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex -> {
                        SupportCaseException sce = (SupportCaseException) ex;
                        assertThat(sce.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(sce.getErrorCode()).isEqualTo("SUPPORT_CASE_ALREADY_CLOSED");
                    });

            verify(supportNoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("case not found → HTTP 404")
        void addNote_caseNotFound_throws404() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addNote(CASE_ID,
                    SupportNoteCreateRequestDTO.builder()
                            .noteText("x").authorUserId(AUTHOR_ID).build()))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex -> {
                        assertThat(((SupportCaseException) ex).getHttpStatus())
                                .isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. Assign Case
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. AssignCase")
    class AssignCase {

        @Test
        @DisplayName("assigning OPEN case → transitions to IN_PROGRESS, writes timeline")
        void assign_openCase_transitionsToInProgress() {
            SupportCase sc = openCase();
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(sc));
            when(supportCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SupportCaseAssignRequestDTO req = SupportCaseAssignRequestDTO.builder()
                    .ownerUserId(OWNER_ID).build();

            SupportCaseResponseDTO dto = service.assignCase(CASE_ID, req);

            assertThat(dto.getOwnerUserId()).isEqualTo(OWNER_ID);
            assertThat(dto.getStatus()).isEqualTo(SupportCaseStatus.IN_PROGRESS);

            verify(timelineService).appendManual(argThat(ev ->
                    "SUPPORT_CASE_ASSIGNED".equals(ev.getEventType())));
        }

        @Test
        @DisplayName("assigning IN_PROGRESS case keeps status unchanged")
        void assign_inProgressCase_statusUnchanged() {
            SupportCase sc = openCase();
            sc.setStatus(SupportCaseStatus.IN_PROGRESS);
            sc.setOwnerUserId(77L);
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(sc));
            when(supportCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SupportCaseAssignRequestDTO req = SupportCaseAssignRequestDTO.builder()
                    .ownerUserId(OWNER_ID).build();

            SupportCaseResponseDTO dto = service.assignCase(CASE_ID, req);

            assertThat(dto.getStatus()).isEqualTo(SupportCaseStatus.IN_PROGRESS);
            assertThat(dto.getOwnerUserId()).isEqualTo(OWNER_ID);
        }

        @Test
        @DisplayName("closed case rejects assignment → HTTP 409")
        void assign_closedCase_throws409() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(closedCase()));

            assertThatThrownBy(() -> service.assignCase(CASE_ID,
                    SupportCaseAssignRequestDTO.builder().ownerUserId(OWNER_ID).build()))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex ->
                        assertThat(((SupportCaseException) ex).getHttpStatus())
                                .isEqualTo(HttpStatus.CONFLICT));

            verify(supportCaseRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Close Case
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. CloseCase")
    class CloseCase {

        @Test
        @DisplayName("happy path — open case transitions to CLOSED, timeline written")
        void closeCase_openCase_success() {
            SupportCase sc = openCase();
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(sc));
            when(supportCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SupportCaseResponseDTO dto = service.closeCase(CASE_ID);

            assertThat(dto.getStatus()).isEqualTo(SupportCaseStatus.CLOSED);

            verify(timelineService).appendManual(argThat(ev ->
                    "SUPPORT_CASE_CLOSED".equals(ev.getEventType())));
        }

        @Test
        @DisplayName("double-close rejected → HTTP 409")
        void closeCase_alreadyClosed_throws409() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(closedCase()));

            assertThatThrownBy(() -> service.closeCase(CASE_ID))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex -> {
                        SupportCaseException sce = (SupportCaseException) ex;
                        assertThat(sce.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(sce.getErrorCode()).isEqualTo("SUPPORT_CASE_ALREADY_CLOSED");
                    });

            verify(supportCaseRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. Timeline Linkage
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. TimelineLinkage")
    class TimelineLinkage {

        @Test
        @DisplayName("createCase writes SUPPORT_CASE_OPENED event on linked entity")
        void createCase_timelineEvent_entityAndType() {
            when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(true);
            when(supportCaseRepository.save(any())).thenReturn(openCase());

            service.createCase(SupportCaseCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.CUSTOMER)
                    .linkedEntityId(CUSTOMER_ID)
                    .title(TITLE)
                    .build());

            ArgumentCaptor<TimelineEvent> cap = ArgumentCaptor.forClass(TimelineEvent.class);
            verify(timelineService).appendManual(cap.capture());

            TimelineEvent ev = cap.getValue();
            assertThat(ev.getEntityType()).isEqualTo(TimelineEntityTypes.CUSTOMER);
            assertThat(ev.getEntityId()).isEqualTo(CUSTOMER_ID);
            assertThat(ev.getEventType()).isEqualTo("SUPPORT_CASE_OPENED");
            assertThat(ev.getRelatedEntityType()).isEqualTo(TimelineEntityTypes.SUPPORT_CASE);
            assertThat(ev.getRelatedEntityId()).isEqualTo(CASE_ID);
        }

        @Test
        @DisplayName("closeCase writes SUPPORT_CASE_CLOSED event on linked entity")
        void closeCase_timelineEvent_closedType() {
            SupportCase sc = openCase();
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(sc));
            when(supportCaseRepository.save(any())).thenAnswer(inv -> {
                SupportCase saved = inv.getArgument(0);
                saved.setStatus(SupportCaseStatus.CLOSED);
                return saved;
            });

            service.closeCase(CASE_ID);

            ArgumentCaptor<TimelineEvent> cap = ArgumentCaptor.forClass(TimelineEvent.class);
            verify(timelineService).appendManual(cap.capture());
            assertThat(cap.getValue().getEventType()).isEqualTo("SUPPORT_CASE_CLOSED");
        }

        @Test
        @DisplayName("timeline failure is swallowed — primary operation succeeds")
        void createCase_timelineFailure_doesNotRollbackCase() {
            when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(true);
            when(supportCaseRepository.save(any())).thenReturn(openCase());
            doThrow(new RuntimeException("Redis down")).when(timelineService).appendManual(any());

            // Should NOT throw despite timeline failure
            assertThatCode(() -> service.createCase(SupportCaseCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID)
                    .linkedEntityType(TimelineEntityTypes.CUSTOMER)
                    .linkedEntityId(CUSTOMER_ID)
                    .title(TITLE)
                    .build()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SUPPORT_CASE entity type constant added to TimelineEntityTypes")
        void supportCaseEntityTypeConstantExists() {
            assertThat(TimelineEntityTypes.SUPPORT_CASE).isEqualTo("SUPPORT_CASE");
        }

        @Test
        @DisplayName("RECON_MISMATCH entity type constant added to TimelineEntityTypes")
        void reconMismatchEntityTypeConstantExists() {
            assertThat(TimelineEntityTypes.RECON_MISMATCH).isEqualTo("RECON_MISMATCH");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. List / Get
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. ListAndGet")
    class ListAndGet {

        @Test
        @DisplayName("getCase returns DTO when found")
        void getCase_found() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(openCase()));

            SupportCaseResponseDTO dto = service.getCase(CASE_ID);
            assertThat(dto.getId()).isEqualTo(CASE_ID);
        }

        @Test
        @DisplayName("getCase not found → HTTP 404")
        void getCase_notFound() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCase(CASE_ID))
                    .isInstanceOf(SupportCaseException.class)
                    .satisfies(ex ->
                        assertThat(((SupportCaseException) ex).getHttpStatus())
                                .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("listCases with status filter delegates to correct repo method")
        void listCases_withStatusFilter() {
            when(supportCaseRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                    MERCHANT_ID, SupportCaseStatus.OPEN))
                    .thenReturn(List.of(openCase()));

            List<SupportCaseResponseDTO> result = service.listCases(
                    MERCHANT_ID, SupportCaseStatus.OPEN, null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo(SupportCaseStatus.OPEN);
        }

        @Test
        @DisplayName("listNotes returns notes in creation order")
        void listNotes_returnedInOrder() {
            when(supportCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(openCase()));
            when(supportNoteRepository.findByCaseIdOrderByCreatedAtAsc(CASE_ID))
                    .thenReturn(List.of(sampleNote(CASE_ID)));

            List<SupportNoteResponseDTO> notes = service.listNotes(CASE_ID);
            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).getCaseId()).isEqualTo(CASE_ID);
        }
    }
}
