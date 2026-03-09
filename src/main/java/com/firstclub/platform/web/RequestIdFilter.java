package com.firstclub.platform.web;

import com.firstclub.platform.logging.MdcUtil;
import com.firstclub.platform.logging.StructuredLogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter responsible for a single concern: the {@code X-Request-Id} header.
 *
 * <h3>Behaviour</h3>
 * <ol>
 *   <li>If {@code X-Request-Id} is present and non-blank, use it verbatim.</li>
 *   <li>Otherwise, generate a UUIDv4.</li>
 *   <li>Store in MDC under {@link StructuredLogFields#REQUEST_ID} ({@code "requestId"}).</li>
 *   <li>Echo the value on the response so clients can correlate their own
 *       log entries with server-side traces.</li>
 *   <li>Remove the MDC key in {@code finally} to prevent thread-local leaks in
 *       servlet container thread pools.</li>
 * </ol>
 *
 * <h3>Order</h3>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE} so every other filter in the chain
 * can read {@code requestId} from MDC as early as possible.
 *
 * <h3>Why a dedicated filter?</h3>
 * Isolating the X-Request-Id concern makes each filter independently testable
 * and replaceable without modifying the others.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Header name expected from the client and echoed on the response. */
    public static final String HEADER_NAME = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String requestId = resolveRequestId(request);

        MdcUtil.set(StructuredLogFields.REQUEST_ID, requestId);
        response.setHeader(HEADER_NAME, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MdcUtil.remove(StructuredLogFields.REQUEST_ID);
        }
    }

    // ── Package-visible for testing ───────────────────────────────────────────

    String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        return (header != null && !header.isBlank()) ? header.trim() : UUID.randomUUID().toString();
    }
}
