package com.firstclub.platform.repair.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Request body for repair action endpoints that accept extra parameters.
 *
 * @param dryRun       when {@code true} the action computes but does not persist
 * @param reason       optional human-readable reason logged in the audit trail
 * @param actorUserId  optional id of the admin invoking the repair (may be supplied
 *                     from a JWT claim by the controller instead)
 * @param params       arbitrary action-specific parameters (e.g. {@code date},
 *                     {@code merchantId}, {@code projectionName})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RepairRequestDTO(
        boolean dryRun,
        String reason,
        Long actorUserId,
        Map<String, String> params
) {
    public RepairRequestDTO {
        params = params != null ? Map.copyOf(params) : Map.of();
    }
}
