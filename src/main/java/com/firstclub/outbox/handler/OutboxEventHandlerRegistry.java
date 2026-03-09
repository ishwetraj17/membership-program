package com.firstclub.outbox.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.firstclub.outbox.OutboxEventType;

/**
 * Collects all {@link OutboxEventHandler} beans and provides O(1) lookup by
 * event type.
 *
 * <p>Spring injects every component that implements {@link OutboxEventHandler};
 * the registry builds an immutable map from {@link OutboxEventHandler#getEventType()}
 * to handler instance.  Duplicate event types are detected at startup and cause
 * an {@link IllegalStateException}.
 */
@Component
@Slf4j
public class OutboxEventHandlerRegistry {

    private final Map<String, OutboxEventHandler> handlers;

    public OutboxEventHandlerRegistry(List<OutboxEventHandler> handlerList) {
        Map<String, Long> counts = handlerList.stream()
                .collect(Collectors.groupingBy(OutboxEventHandler::getEventType,
                        Collectors.counting()));
        counts.forEach((type, count) -> {
            if (count > 1) {
                throw new IllegalStateException(
                        "Multiple OutboxEventHandler beans registered for event type '" + type + "'");
            }
        });

        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(OutboxEventHandler::getEventType, Function.identity()));

        log.info("OutboxEventHandlerRegistry initialized with {} handler(s): {}",
                handlers.size(), handlers.keySet());

        // Phase 12: validate that every declared OutboxEventType has a registered handler
        validateRequiredHandlers(this.handlers);
    }

    /**
     * Returns the handler for the given event type, or empty if none is
     * registered.
     */
    public Optional<OutboxEventHandler> resolve(String eventType) {
        return Optional.ofNullable(handlers.get(eventType));
    }

    /**
     * Validates at startup that every constant in {@link OutboxEventType} has a
     * registered handler.  Throws {@link IllegalStateException} listing all
     * missing types so the operator can fix them before the application starts.
     */
    private void validateRequiredHandlers(Map<String, OutboxEventHandler> handlers) {
        List<String> missing = Arrays.stream(OutboxEventType.values())
                .map(OutboxEventType::eventTypeName)
                .filter(type -> !handlers.containsKey(type))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "OutboxEventHandlerRegistry: missing handlers for required event type(s): "
                    + missing
                    + ". Every OutboxEventType constant must have a corresponding @Component handler.");
        }
        log.info("All {} required OutboxEventType(s) have registered handlers",
                OutboxEventType.values().length);
    }
}
