package com.firstclub.platform.web;

import com.firstclub.platform.logging.MdcUtil;
import com.firstclub.platform.logging.StructuredLogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter responsible for a single concern: the {@code X-Correlation-Id} header.
 *
 * <h3>What is a correlation ID?</h3>
 * A correlation ID links <em>multiple</em> HTTP requests that belong to the
 * same logical business flow — for example, a multi-step checkout session,
 * a billing retry cycle, or a partner webhook delivery sequence.
 *
 * <p>The request ID ({@link RequestIdFilter}) identifies one HTTP request.
 * The correlation ID identifies a larger business operation.
 *
 * <h3>Behaviour</h3>
 * <ol>
 *   <li>If {@code X-Correlation-Id} is present and non-blank, use it verbatim.</li>
 *   <li>Otherwise, fall back to the {@code requestId} already set in MDC by
 *       {@link RequestIdFilter} (which runs first at {@code HIGHEST_PRECEDENCE}).</li>
 *   <li>Store in MDC under {@link StructuredLogFields#CORRELATION_ID} ({@code "correlationId"}).</li>
 *   <li>Echo the value on the response.</li>
 *   <li>Remove the MDC key in {@code finally}.</li>
 * </ol>
 *
 * <h3>Order</h3>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE}{@code + 1} — immediately after
 * {@link RequestIdFilter} so the fallback to {@code requestId} is always available.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Header name expected from the client and echoed on the response. */
    public static final String HEADER_NAME = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String correlationId = resolveCorrelationId(request);

        MdcUtil.set(StructuredLogFields.CORRELATION_ID, correlationId);
        response.setHeader(HEADER_NAME, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            MdcUtil.remove(StructuredLogFields.CORRELATION_ID);
        }
    }

    // ── Package-visible for testing ───────────────────────────────────────────

    String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        // Fall back to the request ID already bound by RequestIdFilter
        String requestId = MDC.get(StructuredLogFields.REQUEST_ID);
        return requestId != null ? requestId : "unknown";
    }
}
