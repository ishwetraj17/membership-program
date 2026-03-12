package com.firstclub.platform.slo;

import java.time.LocalDateTime;

/**
 * Point-in-time status of a single SLO.
 *
 * @param sloId         matches {@link SloDefinition#sloId()}
 * @param name          human-readable SLO name
 * @param targetPercent the contractual target percentage
 * @param currentValue  computed current value (percentage or count); null if insufficient data
 * @param status        evaluated status at this instant
 * @param window        description of the measurement window
 * @param notes         human-readable notes about the computation or any caveats
 * @param evaluatedAt   when this status was computed
 */
public record SloStatusEntry(
        String        sloId,
        String        name,
        double        targetPercent,
        Double        currentValue,
        SloStatus     status,
        String        window,
        String        notes,
        LocalDateTime evaluatedAt
) {}
