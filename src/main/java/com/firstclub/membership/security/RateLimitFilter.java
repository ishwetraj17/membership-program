package com.firstclub.membership.security;

import com.firstclub.membership.exception.ApiErrorResponder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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
import java.time.Duration;

/**
 * Per-client token-bucket rate limiting (Bucket4j). Ordered AFTER the security filter chain so
 * the authenticated principal is available — limits are keyed per user when authenticated and
 * per client IP otherwise. In a multi-node deployment this would use the Bucket4j Redis backend
 * so limits are shared across instances.
 */
@Component
@Order(0) // after Spring Security's filter chain (registered at order -100)
public class RateLimitFilter extends OncePerRequestFilter {

    // Bounded + self-expiring so distinct clients can never grow the map without limit.
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(15))
            .build();
    private final long capacity;
    private final Duration refillPeriod;
    private final ApiErrorResponder errorResponder;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(@Value("${rate-limit.capacity:100}") long capacity,
                           @Value("${rate-limit.refill-period-seconds:60}") long refillSeconds,
                           ApiErrorResponder errorResponder,
                           MeterRegistry meterRegistry) {
        this.capacity = capacity;
        this.refillPeriod = Duration.ofSeconds(refillSeconds);
        this.errorResponder = errorResponder;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.get(clientKey(request), k -> newBucket());
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            meterRegistry.counter("membership.ratelimit.rejected").increment();
            response.setHeader("Retry-After", String.valueOf(refillPeriod.toSeconds()));
            errorResponder.write(response, 429, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded — slow down");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs") || path.startsWith("/actuator");
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, refillPeriod)
                .build();
        return Bucket.builder().addLimit(limit).build();
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
