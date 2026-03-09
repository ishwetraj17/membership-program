package com.firstclub.support.service;

import com.firstclub.support.dto.*;
import com.firstclub.support.entity.SupportCaseStatus;

import java.util.List;

/**
 * Business logic surface for the support / ops case management system.
 *
 * <h3>Invariants enforced by all implementations:</h3>
 * <ul>
 *   <li>The linked entity must exist in its own table before a case is opened.</li>
 *   <li>Cases in the {@code CLOSED} state reject notes, reassignment, and
 *       further status mutations.</li>
 *   <li>Notes are immutable once created.</li>
 *   <li>Tenant isolation: {@code merchantId} scopes all queries.</li>
 * </ul>
 */
public interface SupportCaseService {

    /**
     * Open a new support case linked to a platform entity.
     * Validates that the linked entity exists, then writes a timeline event.
     */
    SupportCaseResponseDTO createCase(SupportCaseCreateRequestDTO request);

    /**
     * List cases for a merchant, optionally filtered by status and/or linked entity.
     *
     * @param merchantId       required tenant scope
     * @param status           optional status filter (null = all statuses)
     * @param linkedEntityType optional entity-type filter (null = all types)
     * @param linkedEntityId   optional entity-id filter (ignored when linkedEntityType is null)
     */
    List<SupportCaseResponseDTO> listCases(Long merchantId,
                                            SupportCaseStatus status,
                                            String linkedEntityType,
                                            Long linkedEntityId);

    /**
     * Retrieve a single support case by its primary key.
     *
     * @throws com.firstclub.support.exception.SupportCaseException if not found
     */
    SupportCaseResponseDTO getCase(Long caseId);

    /**
     * Add an immutable note to an open/in-progress case.
     *
     * @throws com.firstclub.support.exception.SupportCaseException if the case is CLOSED
     */
    SupportNoteResponseDTO addNote(Long caseId, SupportNoteCreateRequestDTO request);

    /**
     * List all notes for a case, oldest first.
     */
    List<SupportNoteResponseDTO> listNotes(Long caseId);

    /**
     * Assign (or re-assign) a case to a platform operator.
     *
     * @throws com.firstclub.support.exception.SupportCaseException if the case is CLOSED
     */
    SupportCaseResponseDTO assignCase(Long caseId, SupportCaseAssignRequestDTO request);

    /**
     * Close a case.  Transitions status to {@code CLOSED}, writes a timeline event.
     *
     * @throws com.firstclub.support.exception.SupportCaseException if already CLOSED
     */
    SupportCaseResponseDTO closeCase(Long caseId);
}
