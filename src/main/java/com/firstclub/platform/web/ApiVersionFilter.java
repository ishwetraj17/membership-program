package com.firstclub.platform.web;

import com.firstclub.platform.logging.MdcUtil;
import com.firstclub.platform.logging.StructuredLogFields;
import com.firstclub.platform.version.ApiVersion;
import com.firstclub.platform.version.ApiVersionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter responsible for a single concern: the {@code X-API-Version} header.
 *
 * <h3>Behaviour</h3>
 * <ol>
 *   <li>Read {@code X-API-Version} from the request header.</li>
 *   <li>Parse via {@link ApiVersion#parseOrDefault(String)} — absent or blank
 *       header returns {@link ApiVersion#DEFAULT}.</li>
 *   <li>Store the raw header value in MDC under
 *       {@link StructuredLogFields#API_VERSION} ({@code "apiVersion"}) so every
 *       log line emitted during request processing carries the client-declared
 *       version.</li>
 *   <li>Remove the MDC key in {@code finally}.</li>
 * </ol>
 *
 * <h3>Version-gated code</h3>
 * Business code uses {@link ApiVersionContext#currentOrDefault()} to read the
 * parsed version from the current thread's {@link com.firstclub.platform.context.RequestContextHolder}
 * (bound by {@link com.firstclub.platform.context.RequestContextFilter} which
 * runs just after this filter).
 *
 * <h3>Order</h3>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE}{@code + 2}, after
 * {@link RequestIdFilter} and {@link CorrelationIdFilter} but before
 * {@link com.firstclub.platform.context.RequestContextFilter} which assembles
 * the final {@link com.firstclub.platform.context.RequestContext}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ApiVersionFilter extends OncePerRequestFilter {

    /** Header name expected from the client. */
    public static final String HEADER_NAME = "X-API-Version";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String rawVersion = request.getHeader(HEADER_NAME);
        ApiVersion parsed = ApiVersion.parseOrDefault(rawVersion);

        // Store raw header in MDC; parsedVersion is available via ApiVersionContext
        if (rawVersion != null && !rawVersion.isBlank()) {
            MdcUtil.set(StructuredLogFields.API_VERSION, rawVersion.trim());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MdcUtil.remove(StructuredLogFields.API_VERSION);
        }
    }

    // ── Package-visible for testing ───────────────────────────────────────────

    ApiVersion parseVersion(String raw) {
        return ApiVersion.parseOrDefault(raw);
    }
}
