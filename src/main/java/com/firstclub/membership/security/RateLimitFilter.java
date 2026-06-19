package com.firstclub.membership.security;

import com.firstclub.membership.exception.ApiErrorResponder;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-client rate limiting. Ordered AFTER the security filter chain so the authenticated principal
 * is available — limits are keyed per user when authenticated and per client IP otherwise.
 *
 * <p>The actual counting is delegated to a {@link RateLimiter} strategy: in-process by default
 * ({@link LocalRateLimiter}) and Redis-backed under the {@code redis} profile
 * ({@link RedisRateLimiter}) so limits are shared across pods. This filter only resolves the client
 * key and translates a rejection into an HTTP 429.
 */
@Component
@Order(0) // after Spring Security's filter chain (registered at order -100)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final long refillPeriodSeconds;
    private final ApiErrorResponder errorResponder;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimiter rateLimiter,
                           @Value("${rate-limit.refill-period-seconds:60}") long refillSeconds,
                           ApiErrorResponder errorResponder,
                           MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.refillPeriodSeconds = refillSeconds;
        this.errorResponder = errorResponder;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (rateLimiter.tryConsume(clientKey(request))) {
            chain.doFilter(request, response);
        } else {
            meterRegistry.counter("membership.ratelimit.rejected").increment();
            response.setHeader("Retry-After", String.valueOf(refillPeriodSeconds));
            errorResponder.write(response, 429, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded — slow down");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs") || path.startsWith("/actuator");
    }

    private String clientKey(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return "ip:" + (forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr());
    }
}
