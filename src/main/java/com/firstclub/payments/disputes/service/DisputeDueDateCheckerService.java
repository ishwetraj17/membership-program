package com.firstclub.payments.disputes.service;

import com.firstclub.payments.disputes.dto.DisputeResponseDTO;

import java.util.List;

/**
 * Phase 15 — Evidence deadline visibility service.
 *
 * <p>Returns disputes whose evidence due-by date falls within a configurable
 * look-ahead window so that operations teams can prioritise submissions before
 * chargebacks become irrecoverable losses.
 */
public interface DisputeDueDateCheckerService {

    /**
     * Returns all active (OPEN or UNDER_REVIEW) disputes whose {@code dueBy}
     * is not null and falls before {@code now + withinDays}.
     *
     * @param withinDays number of days ahead to look (e.g. 7 means "due within a week")
     * @return disputes sorted by {@code dueBy} ascending (most urgent first)
     */
    List<DisputeResponseDTO> findDueSoon(int withinDays);
}
