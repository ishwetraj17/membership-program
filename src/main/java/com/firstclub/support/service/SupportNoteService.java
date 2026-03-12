package com.firstclub.support.service;

import com.firstclub.support.dto.SupportNoteCreateRequestDTO;
import com.firstclub.support.dto.SupportNoteResponseDTO;
import com.firstclub.support.entity.SupportCase;
import com.firstclub.support.entity.SupportCaseStatus;
import com.firstclub.support.entity.SupportNote;
import com.firstclub.support.entity.SupportNoteVisibility;
import com.firstclub.support.exception.SupportCaseException;
import com.firstclub.support.repository.SupportCaseRepository;
import com.firstclub.support.repository.SupportNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Dedicated service for {@link SupportNote} lifecycle operations.
 *
 * <p>Responsibility is scoped to note creation, retrieval, and visibility
 * filtering.  Case-level state transitions (assign, close, etc.) remain
 * owned by {@link SupportCaseService}.
 *
 * <p><b>Package:</b> {@code com.firstclub.support.service}
 */
@Service
@RequiredArgsConstructor
public class SupportNoteService {

    private final SupportNoteRepository noteRepository;
    private final SupportCaseRepository caseRepository;

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Adds a note to a support case.
     *
     * <p>Validates that:
     * <ul>
     *   <li>The case exists.</li>
     *   <li>The case is not in {@link SupportCaseStatus#CLOSED} state.</li>
     * </ul>
     *
     * @throws SupportCaseException if the case is not found or is CLOSED
     */
    @Transactional
    public SupportNoteResponseDTO addNote(Long caseId, SupportNoteCreateRequestDTO request) {
        SupportCase supportCase = caseRepository.findById(caseId)
                .orElseThrow(() -> SupportCaseException.caseNotFound(caseId));

        if (SupportCaseStatus.CLOSED.equals(supportCase.getStatus())) {
            throw SupportCaseException.caseAlreadyClosed(caseId);
        }

        SupportNoteVisibility visibility = request.getVisibility() != null
                ? request.getVisibility()
                : SupportNoteVisibility.INTERNAL_ONLY;

        SupportNote note = SupportNote.builder()
                .caseId(caseId)
                .noteText(request.getNoteText())
                .authorUserId(request.getAuthorUserId())
                .visibility(visibility)
                .build();

        SupportNote saved = noteRepository.save(note);
        return toResponseDTO(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns all notes for the given case in chronological order (oldest first).
     */
    @Transactional(readOnly = true)
    public List<SupportNoteResponseDTO> listNotes(Long caseId) {
        return noteRepository.findByCaseIdOrderByCreatedAtAsc(caseId)
                .stream()
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    /**
     * Returns only notes with the specified {@link SupportNoteVisibility},
     * in chronological order (oldest first).
     *
     * <p>Useful to serve the merchant-facing view where only
     * {@code MERCHANT_VISIBLE} notes should appear.
     */
    @Transactional(readOnly = true)
    public List<SupportNoteResponseDTO> listVisibleNotes(Long caseId,
                                                          SupportNoteVisibility visibility) {
        return noteRepository.findByCaseIdOrderByCreatedAtAsc(caseId)
                .stream()
                .filter(n -> visibility.equals(n.getVisibility()))
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private SupportNoteResponseDTO toResponseDTO(SupportNote note) {
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
