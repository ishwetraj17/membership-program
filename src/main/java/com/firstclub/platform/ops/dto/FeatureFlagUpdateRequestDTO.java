package com.firstclub.platform.ops.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

/**
 * Request body for enabling / disabling a feature flag via the admin API.
 * The flagKey comes from the URL path.  Scope defaults to GLOBAL.
 * Provide merchantId in the path to create / update a merchant override.
 */
@Data
@Builder
public class FeatureFlagUpdateRequestDTO {

    @NotNull
    private Boolean enabled;

    /** Optional JSON blob stored alongside the flag (e.g., rollout %). */
    private String configJson;
}
