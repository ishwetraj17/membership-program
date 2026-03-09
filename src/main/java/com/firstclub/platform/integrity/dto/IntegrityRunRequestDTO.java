package com.firstclub.platform.integrity.dto;

/**
 * Request body for triggering an integrity check run.
 *
 * @param merchantId         Optional merchant scope; {@code null} runs globally.
 * @param initiatedByUserId  Optional user ID of the admin triggering the run.
 */
public record IntegrityRunRequestDTO(
        Long merchantId,
        Long initiatedByUserId
) {}
