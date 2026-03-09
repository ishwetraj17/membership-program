package com.firstclub.platform.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestContextHolder")
class RequestContextHolderTest {

    @AfterEach
    void clearAfterEach() {
        // Guard against any test that forgets to clear, preventing leaks into
        // subsequent tests that run on the same thread.
        RequestContextHolder.clear();
    }

    // ── set / current ────────────────────────────────────────────────────────

    @Test
    @DisplayName("current() returns empty when nothing is set")
    void current_emptyWhenNotSet() {
        assertThat(RequestContextHolder.current()).isEmpty();
    }

    @Test
    @DisplayName("current() returns the context that was set")
    void current_returnsSetContext() {
        RequestContext ctx = RequestContext.builder()
                .requestId("req-abc")
                .correlationId("corr-abc")
                .build();

        RequestContextHolder.set(ctx);

        Optional<RequestContext> result = RequestContextHolder.current();
        assertThat(result).isPresent();
        assertThat(result.get().getRequestId()).isEqualTo("req-abc");
    }

    @Test
    @DisplayName("set() replaces any previously set context")
    void set_replacesExistingContext() {
        RequestContext first = RequestContext.builder().requestId("first").correlationId("c1").build();
        RequestContext second = RequestContext.builder().requestId("second").correlationId("c2").build();

        RequestContextHolder.set(first);
        RequestContextHolder.set(second);

        assertThat(RequestContextHolder.current().map(RequestContext::getRequestId))
                .hasValue("second");
    }

    // ── require ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("require() returns context when present")
    void require_returnsContextWhenPresent() {
        RequestContext ctx = RequestContext.builder().requestId("r1").correlationId("c1").build();
        RequestContextHolder.set(ctx);

        assertThat(RequestContextHolder.require().getRequestId()).isEqualTo("r1");
    }

    @Test
    @DisplayName("require() throws IllegalStateException when context is absent")
    void require_throwsWhenAbsent() {
        assertThatThrownBy(RequestContextHolder::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RequestContext");
    }

    // ── clear ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("clear() removes the context so current() returns empty")
    void clear_removesContext() {
        RequestContextHolder.set(RequestContext.builder().requestId("r1").correlationId("c1").build());

        RequestContextHolder.clear();

        assertThat(RequestContextHolder.current()).isEmpty();
    }

    @Test
    @DisplayName("clear() is idempotent — safe to call when no context is set")
    void clear_idempotent() {
        // Should not throw
        RequestContextHolder.clear();
        RequestContextHolder.clear();
    }

    // ── Thread isolation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("context set in thread A is not visible in thread B")
    void threadLocal_isolatesBetweenThreads() throws InterruptedException {
        RequestContext ctxA = RequestContext.builder().requestId("thread-a").correlationId("c-a").build();
        RequestContextHolder.set(ctxA);

        AtomicReference<Optional<RequestContext>> threadBResult = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread threadB = new Thread(() -> {
            threadBResult.set(RequestContextHolder.current());
            latch.countDown();
        });

        threadB.start();
        latch.await();

        // Thread A still has its own context
        assertThat(RequestContextHolder.require().getRequestId()).isEqualTo("thread-a");
        // Thread B should NOT see thread A's context
        assertThat(threadBResult.get()).as("Thread B must not see Thread A's RequestContext").isEmpty();
    }

    @Test
    @DisplayName("clear() in thread A does not remove context in thread B")
    void clear_doesNotAffectOtherThreads() throws InterruptedException {
        RequestContext ctxB = RequestContext.builder().requestId("thread-b").correlationId("c-b").build();
        AtomicReference<String> requestIdAfterClear = new AtomicReference<>();
        CountDownLatch latchB = new CountDownLatch(1);
        CountDownLatch latchClear = new CountDownLatch(1);

        Thread threadB = new Thread(() -> {
            RequestContextHolder.set(ctxB);
            // Signal main thread that thread B has set its context
            latchClear.countDown();
            try {
                // Wait for main thread to call clear()
                latchB.await();
                requestIdAfterClear.set(
                    RequestContextHolder.current().map(RequestContext::getRequestId).orElse("MISSING"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        threadB.start();
        latchClear.await();            // wait for thread B to set context
        RequestContextHolder.clear();  // clear on main thread
        latchB.countDown();            // let thread B read its context

        threadB.join();

        // Thread B's context must still be present after main thread called clear()
        assertThat(requestIdAfterClear.get()).isEqualTo("thread-b");
    }
}
