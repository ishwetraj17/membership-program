package com.firstclub.support.service.impl;

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
import com.firstclub.support.service.SupportCaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link SupportCaseService}.
 *
 * <h3>Linked-entity validation</h3>
 * Before creating a case the service verifies that the referenced entity
 * actually exists, dispatching on the {@code linkedEntityType} string to
 * the appropriate Spring Data repository.  Unknown type strings are
 * rejected with HTTP 400; known types where the ID is absent return HTTP 422.
 *
 * <h3>Timeline integration</h3>
 * Every state-changing operation (open, assign, close) appends a row to the
 * ops timeline so that the linked entity's history view surfaces the case
 * activity via {@link com.firstclub.reporting.ops.timeline.controller.TimelineController}.
 *
 * <h3>Mutation guard</h3>
 * {@code CLOSED} cases reject all mutations (addNote, assignCase, closeCase)
 * to preserve the audit trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupportCaseServiceImpl implements SupportCaseService {

    private final SupportCaseRepository     supportCaseRepository;
    private final SupportNoteRepository     supportNoteRepository;
    private final TimelineService           timelineService;

    // ── Repositories used for linked-entity existence validation ─────────────
    private final CustomerRepository        customerRepository;
    private final SubscriptionV2Repository  subscriptionV2Repository;
    private final InvoiceRepository         invoiceRepository;
    private final PaymentIntentV2Repository paymentIntentV2Repository;
    private final RefundV2Repository        refundV2Repository;
    private final DisputeRepository         disputeRepository;
    private final ReconMismatchRepository   reconMismatchRepository;

    // ── Write path ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SupportCaseResponseDTO createCase(SupportCaseCreateRequestDTO request) {
        log.info("Opening support case for merchant={} entity={}/{} title='{}'",
                request.getMerchantId(),
                request.getLinkedEntityType(),
                request.getLinkedEntityId(),
                request.getTitle());

        validateLinkedEntityExists(request.getLinkedEntityType(), request.getLinkedEntityId());

        SupportCasePriority priority = request.getPriority() != null
                ? request.getPriority()
                : SupportCasePriority.MEDIUM;

        SupportCase saved = supportCaseRepository.save(
                SupportCase.builder()
                        .merchantId(request.getMerchantId())
                        .linkedEntityType(request.getLinkedEntityType().toUpperCase())
                        .linkedEntityId(request.getLinkedEntityId())
                        .title(request.getTitle())
                        .status(SupportCaseStatus.OPEN)
                        .priority(priority)
                        .build()
        );

        appendTimeline(saved, "SUPPORT_CASE_OPENED",
                "Support case opened: " + saved.getTitle(),
                "Priority: " + saved.getPriority());

        log.info("Support case id={} opened for entity={}/{}", saved.getId(),
                saved.getLinkedEntityType(), saved.getLinkedEntityId());
        return toDto(saved);
    }

    @Override
    @Transactional
    public SupportNoteResponseDTO addNote(Long caseId, SupportNoteCreateRequestDTO request) {
        SupportCase sc = loadCaseOrThrow(caseId);
        guardNotClosed(sc);

        SupportNoteVisibility visibility = request.getVisibility() != null
                ? request.getVisibility()
                : SupportNoteVisibility.INTERNAL_ONLY;

        SupportNote saved = supportNoteRepository.save(
                SupportNote.builder()
                        .caseId(caseId)
                        .noteText(request.getNoteText())
                        .authorUserId(request.getAuthorUserId())
                        .visibility(visibility)
                        .build()
        );

        appendTimeline(sc, "SUPPORT_CASE_NOTE_ADDED",
                "Note added to support case: " + sc.getTitle(),
                "by user " + request.getAuthorUserId());

        log.info("Note id={} added to case id={} by user={}", saved.getId(), caseId,
                request.getAuthorUserId());
        return toNoteDto(saved);
    }

    @Override
    @Transactional
    public SupportCaseResponseDTO assignCase(Long caseId, SupportCaseAssignRequestDTO request) {
        SupportCase sc = loadCaseOrThrow(caseId);
        guardNotClosed(sc);

        sc.setOwnerUserId(request.getOwnerUserId());
        if (sc.getStatus() == SupportCaseStatus.OPEN) {
            sc.setStatus(SupportCaseStatus.IN_PROGRESS);
        }
        SupportCase saved = supportCaseRepository.save(sc);

        appendTimeline(saved, "SUPPORT_CASE_ASSIGNED",
                "Support case assigned: " + saved.getTitle(),
                "Assigned to user " + request.getOwnerUserId());

        log.info("Case id={} assigned to user={}", caseId, request.getOwnerUserId());
        return toDto(saved);
    }

    @Override
    @Transactional
    public SupportCaseResponseDTO closeCase(Long caseId) {
        SupportCase sc = loadCaseOrThrow(caseId);
        if (sc.isClosed()) {
            throw SupportCaseException.caseAlreadyClosed(caseId);
        }

        sc.setStatus(SupportCaseStatus.CLOSED);
        SupportCase saved = supportCaseRepository.save(sc);

        appendTimeline(saved, "SUPPORT_CASE_CLOSED",
                "Support case closed: " + saved.getTitle(),
                null);

        log.info("Case id={} closed", caseId);
        return toDto(saved);
    }

    // ── Read path ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SupportCaseResponseDTO> listCases(Long merchantId,
                                                    SupportCaseStatus status,
                                                    String linkedEntityType,
                                                    Long linkedEntityId) {
        List<SupportCase> cases;
        if (linkedEntityType != null && linkedEntityId != null && status != null) {
            cases = supportCaseRepository
                    .findByMerchantIdAndLinkedEntityTypeAndLinkedEntityIdAndStatusOrderByCreatedAtDesc(
                            merchantId, linkedEntityType.toUpperCase(), linkedEntityId, status);
        } else if (linkedEntityType != null && linkedEntityId != null) {
            cases = supportCaseRepository
                    .findByMerchantIdAndLinkedEntityTypeAndLinkedEntityIdOrderByCreatedAtDesc(
                            merchantId, linkedEntityType.toUpperCase(), linkedEntityId);
        } else if (status != null) {
            cases = supportCaseRepository
                    .findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, status);
        } else {
            cases = supportCaseRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
        }
        return cases.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public SupportCaseResponseDTO getCase(Long caseId) {
        return toDto(loadCaseOrThrow(caseId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportNoteResponseDTO> listNotes(Long caseId) {
        // ensure case exists — implicitly validates tenant access path
        loadCaseOrThrow(caseId);
        return supportNoteRepository.findByCaseIdOrderByCreatedAtAsc(caseId)
                .stream().map(this::toNoteDto).collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SupportCase loadCaseOrThrow(Long caseId) {
        return supportCaseRepository.findById(caseId)
                .orElseThrow(() -> SupportCaseException.caseNotFound(caseId));
    }

    private void guardNotClosed(SupportCase sc) {
        if (sc.isClosed()) {
            throw SupportCaseException.caseAlreadyClosed(sc.getId());
        }
    }

    /**
     * Dispatch on {@code entityType} to the appropriate repository and verify
     * the entity exists.  Throws HTTP 400 for unknown types, HTTP 422 when the
     * known entity is not found.
     */
    private void validateLinkedEntityExists(String entityType, Long entityId) {
        if (entityType == null || entityType.isBlank()) {
            throw SupportCaseException.unknownLinkedEntityType("<null>");
        }
        boolean exists = switch (entityType.toUpperCase()) {
            case TimelineEntityTypes.CUSTOMER       -> customerRepository.existsById(entityId);
            case TimelineEntityTypes.SUBSCRIPTION   -> subscriptionV2Repository.existsById(entityId);
            case TimelineEntityTypes.INVOICE        -> invoiceRepository.existsById(entityId);
            case TimelineEntityTypes.PAYMENT_INTENT -> paymentIntentV2Repository.existsById(entityId);
            case TimelineEntityTypes.REFUND         -> refundV2Repository.existsById(entityId);
            case TimelineEntityTypes.DISPUTE        -> disputeRepository.existsById(entityId);
            case "RECON_MISMATCH"                   -> reconMismatchRepository.existsById(entityId);
            default -> throw SupportCaseException.unknownLinkedEntityType(entityType);
        };
        if (!exists) {
            throw SupportCaseException.linkedEntityNotFound(entityType, entityId);
        }
    }

    /** Write a manual timeline event on the linked entity. */
    private void appendTimeline(SupportCase sc, String eventType, String title, String summary) {
        try {
            TimelineEvent event = TimelineEvent.builder()
                    .merchantId(sc.getMerchantId())
                    .entityType(sc.getLinkedEntityType())
                    .entityId(sc.getLinkedEntityId())
                    .eventType(eventType)
                    .eventTime(LocalDateTime.now())
                    .title(title)
                    .summary(summary)
                    .relatedEntityType(TimelineEntityTypes.SUPPORT_CASE)
                    .relatedEntityId(sc.getId())
                    // source_event_id null → manual row, never blocked by dedup index
                    .build();
            timelineService.appendManual(event);
        } catch (Exception ex) {
            // Timeline is derived data; a failure here must not roll back the primary operation.
            log.warn("Failed to append timeline for support case id={} event={}: {}",
                    sc.getId(), eventType, ex.getMessage());
        }
    }

    // ── Mapping helpers ────────────────────────────────────────────────────────

    private SupportCaseResponseDTO toDto(SupportCase sc) {
        return SupportCaseResponseDTO.builder()
                .id(sc.getId())
                .merchantId(sc.getMerchantId())
                .linkedEntityType(sc.getLinkedEntityType())
                .linkedEntityId(sc.getLinkedEntityId())
                .title(sc.getTitle())
                .status(sc.getStatus())
                .priority(sc.getPriority())
                .ownerUserId(sc.getOwnerUserId())
                .createdAt(sc.getCreatedAt())
                .updatedAt(sc.getUpdatedAt())
                .build();
    }

    private SupportNoteResponseDTO toNoteDto(SupportNote note) {
        return SupportNoteResponseDTO.builder()
                .id(note.getId())
                .caseId(note.getCaseId())
                .noteText(note.getNoteText())
                .authorUserId(note.getAuthorUserId())
                .visibility(note.getVisibility())
                .createdAt(note.getCreatedAt())
                .build();
    }
}
