package com.firstclub.platform.version;

import com.firstclub.platform.context.RequestContextHolder;

/**
 * Resolves the {@link ApiVersion} for the currently executing request thread.
 *
 * <p>Reads the raw version string from the thread-local {@link RequestContextHolder}
 * (populated from the {@code X-API-Version} header by {@code RequestContextFilter}),
 * then parses it into an {@link ApiVersion}.  Falls back to {@link ApiVersion#DEFAULT}
 * when no version header was provided.
 *
 * <h3>No state</h3>
 * This is a pure static utility — the thread-local context is already managed
 * by {@link RequestContextHolder}.  No Spring bean is needed.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   ApiVersion version = ApiVersionContext.currentOrDefault();
 *   if (version.isAfterOrEqual(ApiVersion.V_2025_01)) {
 *       return enrichedResponse(entity);
 *   }
 *   return legacyResponse(entity);
 * }</pre>
 */
public final class ApiVersionContext {

    private ApiVersionContext() { /* static utility */ }

    /**
     * Returns the {@link ApiVersion} for the current request, or
     * {@link ApiVersion#DEFAULT} if no {@code X-API-Version} header was sent.
     *
     * <p>Returns {@link ApiVersion#DEFAULT} rather than throwing when called
     * from async/non-request threads (e.g., scheduled jobs) so callers do
     * not need to guard against absent context.
     */
    public static ApiVersion currentOrDefault() {
        return RequestContextHolder.current()
                .flatMap(ctx -> ctx.apiVersionOpt())
                .map(ApiVersion::parseOrDefault)
                .orElse(ApiVersion.DEFAULT);
    }
}
