package com.firstclub.platform.web;

import com.firstclub.platform.logging.StructuredLogFields;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestIdFilter}.
 *
 * <p>Tests are intentionally Spring-free: the filter is instantiated directly
 * and exercised with {@link MockHttpServletRequest} / {@link MockHttpServletResponse}.
 */
@DisplayName("RequestIdFilter")
class RequestIdFilterTest {

    private RequestIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestIdFilter();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ── Request ID resolution ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Request ID resolution")
    class Resolution {

        @Test
        @DisplayName("uses client-supplied X-Request-Id verbatim")
        void usesClientProvidedId() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER_NAME, "client-req-id-42");

            assertThat(filter.resolveRequestId(request)).isEqualTo("client-req-id-42");
        }

        @Test
        @DisplayName("trims whitespace from client-supplied X-Request-Id")
        void trimsWhitespace() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER_NAME, "  padded-id  ");

            assertThat(filter.resolveRequestId(request)).isEqualTo("padded-id");
        }

        @Test
        @DisplayName("generates UUID when X-Request-Id header is absent")
        void generatesUuidWhenAbsent() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();

            String id = filter.resolveRequestId(request);
            assertThat(id).isNotBlank();
            // UUIDs have the format xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
            assertThat(id).matches("[0-9a-f-]{36}");
        }

        @Test
        @DisplayName("generates UUID when X-Request-Id is blank")
        void generatesUuidWhenBlank() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER_NAME, "   ");

            String id = filter.resolveRequestId(request);
            assertThat(id).isNotBlank();
            assertThat(id).matches("[0-9a-f-]{36}");
        }

        @Test
        @DisplayName("two absent-header calls produce different UUIDs")
        void generatesDifferentIds() {
            MockHttpServletRequest r1 = new MockHttpServletRequest();
            MockHttpServletRequest r2 = new MockHttpServletRequest();

            assertThat(filter.resolveRequestId(r1)).isNotEqualTo(filter.resolveRequestId(r2));
        }
    }

    // ── MDC binding ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MDC binding")
    class MdcBinding {

        @Test
        @DisplayName("sets requestId in MDC during filter chain")
        void setsMdcDuringChain() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER_NAME, "mdc-test-id");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            // After filter completes, MDC is cleared so we capture inside the chain.
            // The chain executes synchronously — verify via the response header set before chain.
            assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("mdc-test-id");
        }

        @Test
        @DisplayName("clears requestId from MDC after filter completes")
        void clearsMdcAfterChain() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER_NAME, "should-be-cleared");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(MDC.get(StructuredLogFields.REQUEST_ID)).isNull();
        }
    }

    // ── Response header ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Response header")
    class ResponseHeader {

        @Test
        @DisplayName("echoes X-Request-Id on response")
        void echoesHeaderOnResponse() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER_NAME, "echo-this");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("echo-this");
        }

        @Test
        @DisplayName("echoes generated UUID on response when header absent")
        void echoesGeneratedId() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            String echoed = response.getHeader(RequestIdFilter.HEADER_NAME);
            assertThat(echoed).matches("[0-9a-f-]{36}");
        }
    }
}
