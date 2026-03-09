package com.firstclub.platform.web;

import com.firstclub.platform.logging.StructuredLogFields;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestLoggingFilter}.
 */
@DisplayName("RequestLoggingFilter")
class RequestLoggingFilterTest {

    private RequestLoggingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ── Actuator exclusion ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Actuator exclusion")
    class ActuatorExclusion {

        @Test
        @DisplayName("skips /actuator requests")
        void skipsActuatorPath() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/actuator/health");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("skips /actuator/metrics requests")
        void skipsActuatorMetricsPath() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/actuator/metrics");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("does not skip normal API requests")
        void doesNotSkipApiRequests() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/api/v2/subscriptions");

            assertThat(filter.shouldNotFilter(request)).isFalse();
        }

        @Test
        @DisplayName("does not skip root path")
        void doesNotSkipRoot() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/");

            assertThat(filter.shouldNotFilter(request)).isFalse();
        }
    }

    // ── Filter execution ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Filter execution")
    class FilterExecution {

        @Test
        @DisplayName("proceeds through filter chain normally")
        void proceedsThroughChain() throws Exception {
            MDC.put(StructuredLogFields.REQUEST_ID, "log-req-id");
            MDC.put(StructuredLogFields.CORRELATION_ID, "log-corr-id");

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod("GET");
            request.setRequestURI("/api/v2/plans");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(200);

            final boolean[] chainInvoked = {false};
            FilterChain chain = (req, res) -> chainInvoked[0] = true;

            filter.doFilter(request, response, chain);

            assertThat(chainInvoked[0]).isTrue();
        }

        @Test
        @DisplayName("proceeds through chain even when MDC has no request context")
        void proceedsThroughChainWithoutMdc() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod("POST");
            request.setRequestURI("/api/v2/subscriptions");
            MockHttpServletResponse response = new MockHttpServletResponse();

            final boolean[] chainInvoked = {false};
            FilterChain chain = (req, res) -> chainInvoked[0] = true;

            filter.doFilter(request, response, chain);

            assertThat(chainInvoked[0]).isTrue();
        }

        @Test
        @DisplayName("proceeds through chain even when downstream throws")
        void proceedsThroughChainOnException() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod("DELETE");
            request.setRequestURI("/api/v2/plans/99");
            MockHttpServletResponse response = new MockHttpServletResponse();

            FilterChain throwingChain = (req, res) -> {
                throw new RuntimeException("downstream failure");
            };

            // Exception propagates; logging still happens in finally
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    filter.doFilter(request, response, throwingChain))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
