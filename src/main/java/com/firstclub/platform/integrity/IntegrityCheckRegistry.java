package com.firstclub.platform.integrity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry of all registered {@link IntegrityChecker} instances.
 *
 * <p>All checkers discovered by Spring's component scan are automatically
 * injected via the constructor.  The registry provides access by invariant key
 * and supports listing all registered checkers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityCheckRegistry {

    private final List<IntegrityChecker> allCheckers;

    /** Returns all registered checkers in registration order. */
    public List<IntegrityChecker> getAll() {
        return Collections.unmodifiableList(allCheckers);
    }

    /**
     * Look up a checker by its {@link IntegrityChecker#getInvariantKey()}.
     *
     * @param invariantKey key to search for
     * @return present if a matching checker is registered
     */
    public Optional<IntegrityChecker> findByKey(String invariantKey) {
        return allCheckers.stream()
                .filter(c -> c.getInvariantKey().equals(invariantKey))
                .findFirst();
    }

    /**
     * Returns a summary map: {@code invariantKey → severity} for all registered
     * checkers (useful for diagnostic endpoints).
     */
    public Map<String, IntegrityCheckSeverity> getSeverityMap() {
        Map<String, IntegrityCheckSeverity> map = new LinkedHashMap<>();
        allCheckers.forEach(c -> map.put(c.getInvariantKey(), c.getSeverity()));
        return Collections.unmodifiableMap(map);
    }

    /** @return total number of registered checkers */
    public int size() {
        return allCheckers.size();
    }
}
