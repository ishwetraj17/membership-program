package com.firstclub.platform.repair;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring-managed registry of all {@link RepairAction} implementations.
 * Actions register themselves automatically via {@code @Component}.
 */
@Component
public class RepairActionRegistry {

    private final Map<String, RepairAction> byKey;

    public RepairActionRegistry(List<RepairAction> actions) {
        this.byKey = actions.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RepairAction::getRepairKey,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate repair action key: " + a.getRepairKey());
                        }
                ));
    }

    /** All registered actions. */
    public List<RepairAction> getAll() {
        return List.copyOf(byKey.values());
    }

    /** Look up by repair key. */
    public Optional<RepairAction> findByKey(String repairKey) {
        return Optional.ofNullable(byKey.get(repairKey));
    }

    /** Number of registered actions. */
    public int size() {
        return byKey.size();
    }
}
