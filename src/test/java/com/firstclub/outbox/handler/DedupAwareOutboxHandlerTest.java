package com.firstclub.outbox.handler;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.platform.dedup.BusinessEffectDedupService;
import com.firstclub.platform.dedup.BusinessEffectType;
import com.firstclub.platform.dedup.DedupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DedupAwareOutboxHandler}.
 *
 * Covers: duplicate event skipped (applyEffect not called),
 * new event applies effect and does NOT call recordEffect itself
 * (dedupService.checkAndRecord handles both), exception propagation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DedupAwareOutboxHandler Unit Tests")
class DedupAwareOutboxHandlerTest {

    @Mock
    private BusinessEffectDedupService dedupService;

    /** Concrete subclass used for testing. */
    private TestableHandler handler;

    private static final String EFFECT      = BusinessEffectType.REFUND_COMPLETED;
    private static final String FINGERPRINT = "aabbccddeeff00112233445566778899aabbccddeeff001122334455667788";

    @BeforeEach
    void setUp() {
        handler = new TestableHandler(FINGERPRINT);
        // Inject the @Autowired dedupService via reflection (no Spring context needed)
        ReflectionTestUtils.setField(handler, "dedupService", dedupService);
    }

    // ── Duplicate event skipped ────────────────────────────────────────────

    @Test
    @DisplayName("handle does NOT call applyEffect when checkAndRecord returns DUPLICATE")
    void handle_duplicate_skipsApplyEffect() throws Exception {
        when(dedupService.checkAndRecord(eq(EFFECT), eq(FINGERPRINT), anyString(), anyLong()))
                .thenReturn(DedupResult.DUPLICATE);

        OutboxEvent event = buildEvent(99L);
        handler.handle(event);

        verify(dedupService).checkAndRecord(EFFECT, FINGERPRINT, "OUTBOX_EVENT", 99L);
        assertThat(handler.applyEffectCallCount).isZero();
    }

    // ── New event applies effect ───────────────────────────────────────────

    @Test
    @DisplayName("handle calls applyEffect exactly once when checkAndRecord returns NEW")
    void handle_new_callsApplyEffect() throws Exception {
        when(dedupService.checkAndRecord(eq(EFFECT), eq(FINGERPRINT), anyString(), anyLong()))
                .thenReturn(DedupResult.NEW);

        OutboxEvent event = buildEvent(42L);
        handler.handle(event);

        verify(dedupService).checkAndRecord(EFFECT, FINGERPRINT, "OUTBOX_EVENT", 42L);
        assertThat(handler.applyEffectCallCount).isOne();
    }

    // ── Exception from applyEffect propagates ─────────────────────────────

    @Test
    @DisplayName("exception thrown by applyEffect propagates to the caller")
    void handle_applyEffectThrows_propagates() {
        when(dedupService.checkAndRecord(any(), any(), any(), any()))
                .thenReturn(DedupResult.NEW);

        TestableHandler throwingHandler = new TestableHandler(FINGERPRINT) {
            @Override
            protected void applyEffect(OutboxEvent evt) throws Exception {
                throw new RuntimeException("simulated transient failure");
            }
        };
        ReflectionTestUtils.setField(throwingHandler, "dedupService", dedupService);

        OutboxEvent event = buildEvent(5L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> throwingHandler.handle(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("transient failure");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static OutboxEvent buildEvent(Long id) {
        return OutboxEvent.builder()
                .id(id)
                .eventType(EFFECT)
                .payload("{}")
                .build();
    }

    /** Minimal concrete implementation of the abstract handler for testing. */
    private static class TestableHandler extends DedupAwareOutboxHandler {

        private final String fingerprint;
        int applyEffectCallCount = 0;

        TestableHandler(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        @Override public String getEventType() { return EFFECT; }
        @Override public String getEffectType() { return EFFECT; }

        @Override
        protected String computeFingerprint(OutboxEvent event) {
            return fingerprint;
        }

        @Override
        protected void applyEffect(OutboxEvent event) throws Exception {
            applyEffectCallCount++;
        }
    }

    // Needed for the assertion in the duplicate-skip test
    private static org.assertj.core.api.AbstractIntegerAssert<?> assertThat(int actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
