package com.firstclub.platform.web;

import com.firstclub.platform.logging.StructuredLogFields;
import com.firstclub.platform.version.ApiVersion;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiVersionFilter}.
 */
@DisplayName("ApiVersionFilter")
class ApiVersionFilterTest {

    private ApiVersionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiVersionFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ── Version parsing ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Version parsing")
    class VersionParsing {

        @Test
        @DisplayName("parses valid YYYY-MM-DD version string")
        void parsesValidVersion() {
            assertThat(filter.parseVersion("2025-01-01"))
                    .isEqualTo(ApiVersion.V_2025_01);
        }

        @Test
        @DisplayName("falls back to DEFAULT when version is null")
        void fallsBackToDefaultWhenNull() {
            assertThat(filter.parseVersion(null))
                    .isEqualTo(ApiVersion.DEFAULT);
        }

        @Test
        @DisplayName("falls back to DEFAULT when version is blank")
        void fallsBackToDefaultWhenBlank() {
            assertThat(filter.parseVersion("  "))
                    .isEqualTo(ApiVersion.DEFAULT);
        }

        @Test
        @DisplayName("falls back to DEFAULT for unrecognised earliest known version")
        void parsesOldVersion() {
            assertThat(filter.parseVersion("2024-01-01"))
                    .isEqualTo(ApiVersion.V_2024_01);
        }
    }

    // ── MDC binding ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("sets apiVersion in MDC when header is present")
        void setsMdcWhenHeaderPresent() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ApiVersionFilter.HEADER_NAME, "2025-01-01");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // Custom chain that captures MDC state during execution
            final String[] capturedVersion = {null};
            FilterChain chain = (req, res) ->
                    capturedVersion[0] = MDC.get(StructuredLogFields.API_VERSION);

            filter.doFilter(request, response, chain);

            assertThat(capturedVersion[0]).isEqualTo("2025-01-01");
        }

        @Test
        @DisplayName("does not set apiVersion in MDC when header is absent")
        void doesNotSetMdcWhenAbsent() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            final String[] capturedVersion = {null};
            FilterChain chain = (req, res) ->
                    capturedVersion[0] = MDC.get(StructuredLogFields.API_VERSION);

            filter.doFilter(request, response, chain);

            assertThat(capturedVersion[0]).isNull();
        }

        @Test
        @DisplayName("clears apiVersion from MDC after filter completes")
        void clearsMdcAfterChain() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ApiVersionFilter.HEADER_NAME, "2025-01-01");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(MDC.get(StructuredLogFields.API_VERSION)).isNull();
        }

        @Test
        @DisplayName("does not leave stale MDC entry when version header is absent")
        void noStaleEntryWhenAbsent() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(MDC.get(StructuredLogFields.API_VERSION)).isNull();
        }
    }
}
