package com.firstclub.platform.idempotency.web;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Optional;

/**
 * Matches incoming HTTP requests to the list of idempotency-covered endpoints
 * using Ant-style path-pattern matching.
 *
 * <p>Serves as an alternative detection mechanism to the
 * {@link com.firstclub.platform.idempotency.annotation.Idempotent @Idempotent}
 * annotation.  When the annotation lookup is unavailable (e.g., in unit tests
 * or when endpoints are defined outside Spring MVC) the filter can fall back to
 * this matcher.
 *
 * <h3>Covered endpoints</h3>
 * <ul>
 *   <li>POST /api/v2/payment-intents</li>
 *   <li>POST /api/v2/payment-intents/{id}/confirm</li>
 *   <li>POST /api/v2/refunds</li>
 *   <li>POST /api/v2/subscriptions</li>
 *   <li>POST /api/v2/invoices/{id}/finalize</li>
 *   <li>POST /api/v2/webhooks/{id}/retry</li>
 *   <li>POST /api/v2/admin/recon/run</li>
 * </ul>
 */
@Component
public class IdempotentEndpointMatcher {

    private static final List<IdempotencyOperationDescriptor> COVERED = List.of(
            new IdempotencyOperationDescriptor("POST", "/api/v*/payment-intents",           true),
            new IdempotencyOperationDescriptor("POST", "/api/v*/payment-intents/*/confirm", true),
            new IdempotencyOperationDescriptor("POST", "/api/v*/refunds",                   true),
            new IdempotencyOperationDescriptor("POST", "/api/v*/subscriptions",             true),
            new IdempotencyOperationDescriptor("POST", "/api/v*/invoices/*/finalize",       true),
            new IdempotencyOperationDescriptor("POST", "/api/v*/webhooks/*/retry",          true),
            new IdempotencyOperationDescriptor("POST", "/api/v*/admin/recon/run",           true)
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Finds the first descriptor whose method and path pattern match the given
     * request.
     *
     * @param method HTTP method (case-insensitive, e.g., {@code "post"} or {@code "POST"})
     * @param path   request path (e.g., {@code "/api/v2/subscriptions"})
     * @return the matching descriptor; empty if no covered endpoint matches
     */
    public Optional<IdempotencyOperationDescriptor> match(String method, String path) {
        return COVERED.stream()
                .filter(d -> d.method().equalsIgnoreCase(method)
                        && pathMatcher.match(d.pathTemplate(), path))
                .findFirst();
    }

    /**
     * Returns {@code true} if the endpoint is covered and requires the
     * {@code Idempotency-Key} header.
     */
    public boolean isKeyRequired(String method, String path) {
        return match(method, path)
                .map(IdempotencyOperationDescriptor::keyRequired)
                .orElse(false);
    }
}
