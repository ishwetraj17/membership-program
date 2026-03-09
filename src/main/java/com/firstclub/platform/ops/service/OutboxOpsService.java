package com.firstclub.platform.ops.service;

import com.firstclub.platform.ops.dto.OutboxLagResponseDTO;

public interface OutboxOpsService {

    /**
     * Returns a summary of the current outbox state: counts per status
     * and per event type for non-DONE events.
     */
    OutboxLagResponseDTO getOutboxLag();
}
