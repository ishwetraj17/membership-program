package com.firstclub.platform.web;

import com.firstclub.platform.logging.StructuredLogFields;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CorrelationIdFilter}.
 */
@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ── Correlation ID resolution ─────────────────────────────────────────────

    @Nested
    @DisplayName("Correlation ID resolution")
    class Resolution {

        @Test
        @DisplayName("uses client-supplied X-Correlation-Id verbatim")
        void usesClientProvidedId() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "flow-session-99");

            assertThat(filter.resolveCorrelationId(request)).isEqualTo("flow-session-99");
        }

        @Test
        @DisplayName("trims whitespace from client-supplied X-Correlation-Id")
        void trimsWhitespace() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "  spaced-corr  ");

            assertThat(filter.resolveCorrelationId(request)).isEqualTo("spaced-corr");
        }

        @Test
        @DisplayName("falls back to MDC requestId when X-Correlation-Id is absent")
        void fallsBackToMdcRequestId() {
            MDC.put(StructuredLogFields.REQUEST_ID, "mdc-req-123");
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThat(filter.resolveCorrelationId(request)).isEqualTo("mdc-req-123");
        }

        @Test
        @DisplayName("falls back to MDC requestId when X-Correlation-Id is blank")
        void fallsBackToMdcRequestIdWhenBlank() {
            MDC.put(StructuredLogFields.REQUEST_ID, "mdc-req-456");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "   ");

            assertThat(filter.resolveCorrelationId(request)).isEqualTo("mdc-req-456");
        }

        @Test
        @DisplayName("returns 'unknown' when header absent and MDC has no requestId")
        void returnsUnknownWhenNothingAvailable() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThat(filter.resolveCorrelationId(request)).isEqualTo("unknown");
        }

        @Test
        @DisplayName("client-supplied correlation ID takes precedence over MDC requestId")
        void clientIdTakesPrecedenceOverMdc() {
            MDC.put(StructuredLogFields.REQUEST_ID, "mdc-req-789");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "explicit-corr");

            assertThat(filter.resolveCorrelationId(request)).isEqualTo("explicit-corr");
        }
    }

    // ── MDC binding ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("clears correlationId from MDC after filter completes")
        void clearsMdcAfterChain() throws Exception {
            MDC.put(StructuredLogFields.REQUEST_ID, "req-for-corr-test");
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(MDC.get(StructuredLogFields.CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("echoes X-Correlation-Id on response")
        void echoesHeaderOnResponse() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "corr-echo");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("corr-echo");
        }
    }
}
