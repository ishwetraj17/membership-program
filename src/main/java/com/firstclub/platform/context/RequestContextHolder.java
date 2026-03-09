package com.firstclub.platform.context;

import java.util.Optional;

/**
 * Thread-local store for the current {@link RequestContext}.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link RequestContextFilter} calls {@link #set(RequestContext)} with a
 *       freshly built context at the start of every request.</li>
 *   <li>The security filter chain may call {@link #set(RequestContext)} again
 *       to upgrade the context with {@code merchantId} and {@code actorId}
 *       after authentication succeeds.</li>
 *   <li>All business code in the same thread uses {@link #current()} or
 *       {@link #require()} to read the context.</li>
 *   <li>{@link RequestContextFilter} calls {@link #clear()} in a
 *       {@code finally} block to prevent thread-local leaks in servlet
 *       container thread pools.</li>
 * </ol>
 *
 * <h3>Async / Virtual threads</h3>
 * Thread-locals are NOT automatically propagated to spawned threads.
 * If you dispatch work to a thread pool (e.g., {@code @Async}, CompletableFuture,
 * scheduled tasks), you must capture the context and re-bind it in that thread:
 * <pre>{@code
 *   RequestContext ctx = RequestContextHolder.current().orElse(null);
 *   executor.submit(() -> {
 *       try {
 *           if (ctx != null) RequestContextHolder.set(ctx);
 *           doWork();
 *       } finally {
 *           RequestContextHolder.clear();
 *       }
 *   });
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * {@link ThreadLocal} is inherently per-thread. The stored {@link RequestContext}
 * is immutable. No external synchronization needed.
 */
public final class RequestContextHolder {

    /*
     * CONCURRENCY NOTE: ThreadLocal<T> provides per-thread isolation.
     * No lock is needed here — each thread reads and writes only its own slot.
     * The clear() call is mandatory to prevent memory leaks in pooled threads.
     */
    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private RequestContextHolder() { /* static utility class */ }

    /**
     * Binds {@code ctx} to the current thread.
     * Any previously bound context for this thread is replaced.
     */
    public static void set(RequestContext ctx) {
        HOLDER.set(ctx);
    }

    /**
     * Returns the context bound to this thread, or {@link Optional#empty()}
     * if no context has been bound (e.g., in async workers, scheduled tasks).
     * Prefer this over {@link #require()} in code that can run outside a
     * request thread.
     */
    public static Optional<RequestContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /**
     * Returns the context bound to this thread.
     *
     * @throws IllegalStateException if no context is bound — signals a
     *         programming error (filter not registered or async boundary
     *         not bridged).
     */
    public static RequestContext require() {
        RequestContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "No RequestContext is bound to the current thread. "
                    + "Ensure RequestContextFilter is registered with the highest precedence, "
                    + "or bridge the context across async boundaries manually.");
        }
        return ctx;
    }

    /**
     * Removes the context from the current thread.
     *
     * <p><strong>MUST</strong> be called in a {@code finally} block at the end
     * of request processing to prevent thread-local leaks in servlet thread pools.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
