package com.firstclub.membership.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that enriches the MDC with HTTP context fields used by the
 * structured JSON log encoder:
 *
 * <ul>
 *   <li>{@code requestPath} — request URI (added <em>before</em> the chain
 *       so that application-level log lines include it)
 *   <li>{@code httpStatus}  — response HTTP status code (set <em>after</em>
 *       the chain, when the status is known)
 *   <li>{@code latencyMs}   — wall-clock latency of the request in
 *       milliseconds (set after the chain)
 * </ul>
 *
 * <p>A single {@code ACCESS} log line is emitted after each request so that
 * request/response metadata is captured in the structured log stream even
 * when no application log is produced.
 *
 * <p>This filter runs after {@link RequestIdFilter} ({@code @Order(1)}) so
 * that the {@code requestId} MDC key is already set when we start.
 */
@Component
@Order(2)
@Slf4j
public class RequestMetricsFilter extends OncePerRequestFilter {

    private static final String MDC_PATH    = "requestPath";
    private static final String MDC_STATUS  = "httpStatus";
    private static final String MDC_LATENCY = "latencyMs";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        long start = System.currentTimeMillis();
        String path = request.getRequestURI();

        MDC.put(MDC_PATH, path);
        try {
            chain.doFilter(request, response);
        } finally {
            long latency = System.currentTimeMillis() - start;
            int  status  = response.getStatus();

            MDC.put(MDC_STATUS,  String.valueOf(status));
            MDC.put(MDC_LATENCY, String.valueOf(latency));

            log.info("ACCESS {} {} {}ms", request.getMethod(), path, latency);

            // Clean up the fields we own; RequestIdFilter owns requestId
            MDC.remove(MDC_PATH);
            MDC.remove(MDC_STATUS);
            MDC.remove(MDC_LATENCY);
        }
    }

    /** Skip actuator and swagger endpoints to avoid log noise. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/v3/api-docs");
    }
}
