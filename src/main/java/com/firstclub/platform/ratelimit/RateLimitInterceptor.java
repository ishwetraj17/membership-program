package com.firstclub.platform.ratelimit;

import com.firstclub.platform.ratelimit.annotation.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Spring MVC {@link HandlerInterceptor} that enforces rate limits declared
 * via the {@link RateLimit} annotation on controller methods.
 *
 * <h3>Subject extraction by policy</h3>
 * <table border="1">
 *   <tr><th>Policy</th><th>Subject(s) extracted</th></tr>
 *   <tr><td>AUTH_BY_IP</td><td>Client IP (resolves X-Forwarded-For)</td></tr>
 *   <tr><td>PAYMENT_CONFIRM</td><td>Path variable {@code merchantId} + authenticated principal</td></tr>
 *   <tr><td>WEBHOOK_INGEST</td><td>Path segment after /webhooks/ + Client IP</td></tr>
 *   <tr><td>APIKEY_GENERAL</td><td>Authenticated principal + API-Key prefix from header</td></tr>
 * </table>
 *
 * <p>AUTH_BY_EMAIL is applied programmatically in {@link
 * com.firstclub.membership.controller.AuthController AuthController} because
 * the email address is only available after the request body is parsed.
 *
 * <h3>Response headers</h3>
 * Every request that reaches a rate-limited endpoint receives:
 * <ul>
 *   <li>{@code X-RateLimit-Limit} — configured limit for the policy</li>
 *   <li>{@code X-RateLimit-Remaining} — remaining slots in the current window</li>
 *   <li>{@code X-RateLimit-Reset} — Unix epoch seconds when the window resets</li>
 * </ul>
 * Blocked requests additionally receive:
 * <ul>
 *   <li>{@code Retry-After} — seconds until the next available slot</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String HEADER_LIMIT     = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET     = "X-RateLimit-Reset";
    public static final String HEADER_RETRY     = "Retry-After";

    private final RateLimitService rateLimitService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit annotation = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> pathVars = (Map<String, String>)
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

        for (RateLimitPolicy policy : annotation.value()) {
            String[] subjects = extractSubjects(policy, request, pathVars);
            RateLimitDecision decision = rateLimitService.checkLimit(policy, subjects);

            // Always add informational headers
            response.setHeader(HEADER_LIMIT,     String.valueOf(decision.limit()));
            response.setHeader(HEADER_REMAINING, String.valueOf(decision.remaining()));
            response.setHeader(HEADER_RESET,     String.valueOf(decision.resetEpochSeconds()));

            if (!decision.allowed()) {
                long retryAfter = Math.max(0,
                        decision.resetAt().getEpochSecond() - System.currentTimeMillis() / 1000);
                response.setHeader(HEADER_RETRY, String.valueOf(retryAfter));

                log.warn("Rate limit exceeded: policy={} key={} ip={}",
                        policy, decision.key(), getClientIp(request));

                throw new RateLimitExceededException(
                        policy,
                        String.join(":", subjects),
                        decision.resetAt());
            }
        }
        return true;
    }

    // ── Subject extraction ────────────────────────────────────────────────────

    private String[] extractSubjects(RateLimitPolicy policy,
                                     HttpServletRequest request,
                                     Map<String, String> pathVars) {
        return switch (policy) {
            case AUTH_BY_IP -> new String[]{ getClientIp(request) };

            case AUTH_BY_EMAIL ->
                    // Not handled in interceptor — email only available after body is parsed.
                    // AuthController calls RateLimitService directly for this policy.
                    new String[]{ getClientIp(request) };

            case PAYMENT_CONFIRM -> {
                String merchantId = getPathVar(pathVars, "merchantId",
                        getPrincipalName(request));
                String customerId = getPrincipalName(request);
                yield new String[]{ merchantId, customerId };
            }

            case WEBHOOK_INGEST -> {
                // Derive "provider" from the last path segment (e.g. /api/v1/webhooks/gateway → "gateway")
                String[] segments = request.getRequestURI().split("/");
                String provider   = segments.length > 0 ? segments[segments.length - 1] : "unknown";
                yield new String[]{ provider, getClientIp(request) };
            }

            case APIKEY_GENERAL -> {
                String principal = getPrincipalName(request);
                String keyPrefix = getApiKeyPrefix(request);
                yield new String[]{ principal, keyPrefix };
            }
        };
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Resolve the real client IP, respecting common reverse-proxy headers.
     * Only the first IP in X-Forwarded-For is used to prevent header injection.
     */
    public static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String getPrincipalName(HttpServletRequest request) {
        return request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName()
                : "anonymous";
    }

    private static String getPathVar(Map<String, String> pathVars,
                                     String name, String fallback) {
        if (pathVars == null) return fallback;
        String val = pathVars.get(name);
        return (val != null && !val.isBlank()) ? val : fallback;
    }

    private static String getApiKeyPrefix(HttpServletRequest request) {
        // Supports "Authorization: ApiKey <key>" or "X-Api-Key: <key>" headers
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.toLowerCase().startsWith("apikey ")) {
            String key = auth.substring(7).trim();
            return key.length() >= 8 ? key.substring(0, 8) : key;
        }
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.length() >= 8 ? apiKey.substring(0, 8) : apiKey;
        }
        return "no-key";
    }
}
