package com.firstclub.platform.context;

import com.firstclub.platform.logging.StructuredLogFields;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestContextFilter}.
 *
 * <p>This filter is the thread-local assembler — it reads values already placed
 * in MDC by the upstream header filters and builds a {@link RequestContext}.
 * Tests pre-seed MDC to simulate the upstream filters having run.
 */
@DisplayName("RequestContextFilter")
class RequestContextFilterTest {

    private RequestContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestContextFilter();
        MDC.clear();
        RequestContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        RequestContextHolder.clear();
    }

    // ── RequestContext assembly ───────────────────────────────────────────────

    @Nested
    @DisplayName("RequestContext assembly")
    class Assembly {

        @Test
        @DisplayName("builds RequestContext from MDC values set by upstream filters")
        void buildsFromMdc() throws Exception {
            MDC.put(StructuredLogFields.REQUEST_ID, "req-from-mdc");
            MDC.put(StructuredLogFields.CORRELATION_ID, "corr-from-mdc");
            MDC.put(StructuredLogFields.API_VERSION, "2025-01-01");

            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            final RequestContext[] captured = {null};
            FilterChain chain = (req, res) ->
                    captured[0] = RequestContextHolder.current().orElse(null);

            filter.doFilter(request, response, chain);

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getRequestId()).isEqualTo("req-from-mdc");
            assertThat(captured[0].getCorrelationId()).isEqualTo("corr-from-mdc");
            assertThat(captured[0].getApiVersion()).isEqualTo("2025-01-01");
        }

        @Test
        @DisplayName("requestId and correlationId are null when MDC has no values")
        void buildsWithNullsWhenMdcEmpty() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            final RequestContext[] captured = {null};
            FilterChain chain = (req, res) ->
                    captured[0] = RequestContextHolder.current().orElse(null);

            filter.doFilter(request, response, chain);

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getRequestId()).isNull();
            assertThat(captured[0].getCorrelationId()).isNull();
            assertThat(captured[0].getApiVersion()).isNull();
        }

        @Test
        @DisplayName("apiVersion is null in context when MDC has no apiVersion")
        void nullApiVersionWhenMdcAbsent() throws Exception {
            MDC.put(StructuredLogFields.REQUEST_ID, "req-no-version");
            MDC.put(StructuredLogFields.CORRELATION_ID, "corr-no-version");

            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            final RequestContext[] captured = {null};
            FilterChain chain = (req, res) ->
                    captured[0] = RequestContextHolder.current().orElse(null);

            filter.doFilter(request, response, chain);

            assertThat(captured[0].getApiVersion()).isNull();
        }
    }

    // ── Thread-local lifecycle ────────────────────────────────────────────────

    @Nested
    @DisplayName("Thread-local lifecycle")
    class ThreadLocalLifecycle {

        @Test
        @DisplayName("RequestContextHolder is populated during filter chain")
        void holderPopulatedDuringChain() throws Exception {
            MDC.put(StructuredLogFields.REQUEST_ID, "in-chain-req");
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            final boolean[] holderPresent = {false};
            FilterChain chain = (req, res) ->
                    holderPresent[0] = RequestContextHolder.current().isPresent();

            filter.doFilter(request, response, chain);

            assertThat(holderPresent[0]).isTrue();
        }

        @Test
        @DisplayName("RequestContextHolder is cleared after filter completes")
        void holderClearedAfterChain() throws Exception {
            MDC.put(StructuredLogFields.REQUEST_ID, "must-be-cleared");
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(RequestContextHolder.current()).isEmpty();
        }

        @Test
        @DisplayName("RequestContextHolder is cleared even when chain throws")
        void holderClearedOnException() {
            MDC.put(StructuredLogFields.REQUEST_ID, "exception-req");
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain throwingChain = (req, res) -> {
                throw new RuntimeException("simulated chain failure");
            };

            try {
                filter.doFilter(request, response, throwingChain);
            } catch (Exception ignored) {
                // Expected
            }

            assertThat(RequestContextHolder.current()).isEmpty();
        }
    }

    // ── Backward-compatible constants ─────────────────────────────────────────

    @Nested
    @DisplayName("Backward-compatible constants")
    class BackwardCompat {

        @Test
        @DisplayName("MDC_REQUEST_ID equals StructuredLogFields.REQUEST_ID")
        void mdcRequestIdConstant() {
            assertThat(RequestContextFilter.MDC_REQUEST_ID)
                    .isEqualTo(StructuredLogFields.REQUEST_ID);
        }

        @Test
        @DisplayName("MDC_CORRELATION_ID equals StructuredLogFields.CORRELATION_ID")
        void mdcCorrelationIdConstant() {
            assertThat(RequestContextFilter.MDC_CORRELATION_ID)
                    .isEqualTo(StructuredLogFields.CORRELATION_ID);
        }

        @Test
        @DisplayName("MDC_API_VERSION equals StructuredLogFields.API_VERSION")
        void mdcApiVersionConstant() {
            assertThat(RequestContextFilter.MDC_API_VERSION)
                    .isEqualTo(StructuredLogFields.API_VERSION);
        }
    }
}
