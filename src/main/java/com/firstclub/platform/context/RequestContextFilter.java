package com.firstclub.platform.context;

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
 * Servlet filter that assembles a {@link RequestContext} from values already
 * set in MDC by the preceding dedicated header filters and binds it to
 * {@link RequestContextHolder} for the duration of the request.
 *
 * <h3>Filter ordering</h3>
 * This filter runs at {@link Ordered#HIGHEST_PRECEDENCE}{@code + 10}, AFTER
 * the three dedicated header filters:
 * <ol>
 *   <li>{@link com.firstclub.platform.web.RequestIdFilter}
 *       ({@link Ordered#HIGHEST_PRECEDENCE}) — sets MDC {@code requestId}</li>
 *   <li>{@link com.firstclub.platform.web.CorrelationIdFilter}
 *       ({@link Ordered#HIGHEST_PRECEDENCE}{@code +1}) — sets MDC {@code correlationId}</li>
 *   <li>{@link com.firstclub.platform.web.ApiVersionFilter}
 *       ({@link Ordered#HIGHEST_PRECEDENCE}{@code +2}) — sets MDC {@code apiVersion}</li>
 * </ol>
 * By the time this filter runs, all three MDC values are already present.
 *
 * <h3>MDC ownership</h3>
 * This filter does NOT set or clear any MDC keys — those are managed
 * exclusively by the dedicated header filters above.  This filter only
 * manages the {@link RequestContextHolder} thread-local lifecycle.
 *
 * <h3>Security / merchantId</h3>
 * {@code merchantId} and {@code actorId} are populated by the security filter
 * chain after JWT/API-key validation via {@link RequestContext#withSecurityContext}.
 *
 * <h3>Thread safety</h3>
 * This filter holds no mutable state.  Every request gets its own immutable
 * {@link RequestContext} stored in a thread-local.
 */
@Component("platformRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestContextFilter extends OncePerRequestFilter {

    // ── Backward-compatible header name constants ─────────────────────────────
    public static final String HEADER_REQUEST_ID     = "X-Request-Id";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_API_VERSION    = "X-API-Version";

    // ── MDC key constants — delegate to StructuredLogFields ───────────────────
    public static final String MDC_REQUEST_ID     = StructuredLogFields.REQUEST_ID;
    public static final String MDC_CORRELATION_ID = StructuredLogFields.CORRELATION_ID;
    public static final String MDC_API_VERSION    = StructuredLogFields.API_VERSION;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // Read values already placed in MDC by the dedicated header filters
        String requestId     = MDC.get(StructuredLogFields.REQUEST_ID);
        String correlationId = MDC.get(StructuredLogFields.CORRELATION_ID);
        String apiVersion    = MDC.get(StructuredLogFields.API_VERSION);

        RequestContext ctx = RequestContext.builder()
                .requestId(requestId)
                .correlationId(correlationId)
                .apiVersion(apiVersion)
                .build();

        RequestContextHolder.set(ctx);

        try {
            chain.doFilter(request, response);
        } finally {
            // MUST clear thread-local to prevent leaks in servlet container thread pools.
            // MDC keys are owned and cleaned up by their respective header filters.
            RequestContextHolder.clear();
        }
    }
}
