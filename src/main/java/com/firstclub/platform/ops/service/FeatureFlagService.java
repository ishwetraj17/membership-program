package com.firstclub.platform.ops.service;

import com.firstclub.platform.ops.dto.FeatureFlagResponseDTO;
import com.firstclub.platform.ops.dto.FeatureFlagUpdateRequestDTO;

import java.util.List;

public interface FeatureFlagService {

    /** Returns the global flag state (no merchant override applied). */
    boolean isEnabled(String flagKey);

    /**
     * Returns the effective flag state for a specific merchant.
     * A merchant-scoped override takes precedence over the global value.
     * Falls back to {@code false} when neither row exists.
     */
    boolean isEnabled(String flagKey, Long merchantId);

    List<FeatureFlagResponseDTO> listFlags();

    /**
     * Creates or updates the global flag identified by {@code flagKey}.
     * If a row already exists it is updated in-place; otherwise a new
     * GLOBAL-scoped row is inserted.
     */
    FeatureFlagResponseDTO updateFlag(String flagKey, FeatureFlagUpdateRequestDTO request);
}
