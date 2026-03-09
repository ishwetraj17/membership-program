package com.firstclub.platform.web;

import com.firstclub.platform.logging.StructuredLogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lightweight HTTP access log filter.
 *
 * <h3>What it logs</h3>
 * One structured log line per request, emitted at INFO level after the
 * response is committed:
 * <pre>
 *   INFO  RequestLoggingFilter - request completed [method=POST path=/api/v2/subscriptions
 *         status=201 durationMs=47 requestId=b2c8... correlationId=b2c8...]
 * </pre>
 *
 * <h3>What it does NOT log</h3>
 * <ul>
 *   <li>Request or response bodies — these can contain PII and payment data.</li>
 *   <li>Headers — these can contain API keys and bearer tokens.</li>
 *   <li>Query parameters — these can contain sensitive filter values.</li>
 * </ul>
 *
 * <h3>Order</h3>
 * Runs at {@link Ordered#LOWEST_PRECEDENCE}{@code - 10} so it wraps the entire
 * request lifecycle and can record the accurate end-to-end duration including
 * all upstream filters and controller processing.
 *
 * <h3>Actuator exclusion</h3>
 * Health-check and actuator endpoints at {@code /actuator/**} are excluded by
 * default — they fire too frequently and add noise to the access log.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        long startNanos = System.nanoTime();

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            logRequest(request, response, durationMs);
        }
    }

    /**
     * Skip actuator traffic — too noisy and adds no business value to the
     * access log.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/actuator");
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void logRequest(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        String method    = request.getMethod();
        String path      = request.getRequestURI();
        int    status    = response.getStatus();
        String requestId    = MDC.get(StructuredLogFields.REQUEST_ID);
        String correlationId = MDC.get(StructuredLogFields.CORRELATION_ID);

        if (log.isInfoEnabled()) {
            log.info("request completed [method={} path={} status={} durationMs={} requestId={} correlationId={}]",
                    method, path, status, durationMs, requestId, correlationId);
        }
    }
}
