package com.firstclub.platform.idempotency.web;

/**
 * Describes a single HTTP endpoint covered by path-based idempotency enforcement.
 *
 * <p>Used by {@link IdempotentEndpointMatcher} as an alternative to the
 * {@link com.firstclub.platform.idempotency.annotation.Idempotent} annotation when
 * annotation-based detection is unavailable (e.g., in certain test configurations)
 * or when a uniform path-pattern list is preferred.
 *
 * @param method       uppercase HTTP method (e.g., {@code "POST"}).
 * @param pathTemplate Ant-style path pattern (e.g., {@code "/api/v2/subscriptions"}).
 * @param keyRequired  if {@code true} the filter returns {@code 400} when the
 *                     {@code Idempotency-Key} header is absent.
 */
public record IdempotencyOperationDescriptor(
        String method,
        String pathTemplate,
        boolean keyRequired) {

    /**
     * Returns the canonical endpoint signature stored in idempotency records and
     * used to detect cross-endpoint key reuse.
     *
     * @return "{method}:{pathTemplate}", e.g. {@code "POST:/api/v2/subscriptions"}
     */
    public String signature() {
        return method + ":" + pathTemplate;
    }
}
