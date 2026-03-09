package com.firstclub.platform.ops.service;

import com.firstclub.platform.ops.dto.DeepHealthResponseDTO;
import com.firstclub.platform.ops.dto.ScalingReadinessDTO;
import com.firstclub.platform.ops.dto.SystemSummaryDTO;

public interface DeepHealthService {

    /**
     * Builds a point-in-time health snapshot by querying every key
     * operational counter without any external calls.
     */
    DeepHealthResponseDTO buildDeepHealthReport();

    /**
     * Aggregates all operational counters into a single summary suitable
     * for dashboards and alert evaluation.
     */
    SystemSummaryDTO buildSystemSummary();

    /**
     * Returns a static architectural description of the system's current
     * shape, known bottlenecks, and a recommended evolution path.
     */
    ScalingReadinessDTO buildScalingReadiness();
}
