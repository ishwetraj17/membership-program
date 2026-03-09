package com.firstclub.membership.ratelimit;

import com.firstclub.platform.ratelimit.RateLimitDecision;
import com.firstclub.platform.ratelimit.RateLimitExceededException;
import com.firstclub.platform.ratelimit.RateLimitInterceptor;
import com.firstclub.platform.ratelimit.RateLimitPolicy;
import com.firstclub.platform.ratelimit.RateLimitService;
import com.firstclub.platform.ratelimit.annotation.RateLimit;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link RateLimitInterceptor}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitInterceptor tests")
class RateLimitInterceptorTest {

    @Mock private RateLimitService rateLimitService;

    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest  request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(rateLimitService);
        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.setRemoteAddr("192.168.1.1");
    }

    // ── Non-controller handlers ───────────────────────────────────────────────

    @Test
    @DisplayName("Non-HandlerMethod objects are passed through")
    void preHandle_nonHandlerMethod_returnsTrue() throws Exception {
        boolean result = interceptor.preHandle(request, response, "not-a-handler");
        assertThat(result).isTrue();
        verifyNoInteractions(rateLimitService);
    }

    // ── No annotation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Methods without @RateLimit are passed through")
    void preHandle_noAnnotation_returnsTrue() throws Exception {
        HandlerMethod handler = handlerMethod("methodWithoutAnnotation");
        boolean result = interceptor.preHandle(request, response, handler);
        assertThat(result).isTrue();
        verifyNoInteractions(rateLimitService);
    }

    // ── Allowed requests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Allowed requests")
    class AllowedTests {

        @BeforeEach
        void setupPermit() {
            RateLimitDecision permit = RateLimitDecision.permit(
                    RateLimitPolicy.AUTH_BY_IP,
                    "dev:firstclub:rl:auth:ip:192.168.1.1",
                    20, 15, Instant.now().plusSeconds(300));
            when(rateLimitService.checkLimit(eq(RateLimitPolicy.AUTH_BY_IP), any()))
                    .thenReturn(permit);
        }

        @Test
        @DisplayName("Returns true and sets X-RateLimit headers")
        void preHandle_allowed_setsHeadersAndReturnsTrue() throws Exception {
            HandlerMethod handler = handlerMethod("authByIpMethod");
            boolean result = interceptor.preHandle(request, response, handler);

            assertThat(result).isTrue();
            assertThat(response.getHeader(RateLimitInterceptor.HEADER_LIMIT)).isEqualTo("20");
            assertThat(response.getHeader(RateLimitInterceptor.HEADER_REMAINING)).isEqualTo("15");
            assertThat(response.getHeader(RateLimitInterceptor.HEADER_RESET)).isNotNull();
        }
    }

    // ── Blocked requests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Blocked requests")
    class BlockedTests {

        @BeforeEach
        void setupDeny() {
            Instant resetAt = Instant.now().plusSeconds(270);
            RateLimitDecision deny = RateLimitDecision.deny(
                    RateLimitPolicy.AUTH_BY_IP,
                    "dev:firstclub:rl:auth:ip:192.168.1.1",
                    20, resetAt);
            when(rateLimitService.checkLimit(eq(RateLimitPolicy.AUTH_BY_IP), any()))
                    .thenReturn(deny);
        }

        @Test
        @DisplayName("Throws RateLimitExceededException when denied")
        void preHandle_denied_throwsException() throws Exception {
            HandlerMethod handler = handlerMethod("authByIpMethod");

            assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                    .isInstanceOf(RateLimitExceededException.class)
                    .extracting(e -> ((RateLimitExceededException) e).getPolicy())
                    .isEqualTo(RateLimitPolicy.AUTH_BY_IP);
        }

        @Test
        @DisplayName("Sets Retry-After header when denied")
        void preHandle_denied_setsRetryAfterHeader() {
            HandlerMethod handler = handlerMethod("authByIpMethod");

            try {
                interceptor.preHandle(request, response, handler);
            } catch (RateLimitExceededException ignored) {
                // expected
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertThat(response.getHeader(RateLimitInterceptor.HEADER_RETRY)).isNotNull();
        }
    }

    // ── IP extraction ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Client IP extraction")
    class IpExtractionTests {

        @Test
        @DisplayName("Uses X-Forwarded-For when present")
        void getClientIp_xff_usesFirstIp() {
            request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
            assertThat(RateLimitInterceptor.getClientIp(request)).isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("Falls back to X-Real-IP when XFF absent")
        void getClientIp_realIp_usedWhenXffAbsent() {
            request.addHeader("X-Real-IP", "198.51.100.7");
            assertThat(RateLimitInterceptor.getClientIp(request)).isEqualTo("198.51.100.7");
        }

        @Test
        @DisplayName("Falls back to remoteAddr when no proxy headers")
        void getClientIp_noHeaders_usesRemoteAddr() {
            assertThat(RateLimitInterceptor.getClientIp(request)).isEqualTo("192.168.1.1");
        }
    }

    // ── Path-variable subject extraction ─────────────────────────────────────

    @Nested
    @DisplayName("PAYMENT_CONFIRM subject extraction")
    class PaymentConfirmSubjectTests {

        @BeforeEach
        void setupAllow() {
            // PAYMENT_CONFIRM has 2 subjects; Mockito 5 spreads varargs so need 2 individual any() matchers
            lenient().when(rateLimitService.checkLimit(
                    eq(RateLimitPolicy.PAYMENT_CONFIRM), any(), any()))
                    .thenReturn(RateLimitDecision.permissive(
                            RateLimitPolicy.PAYMENT_CONFIRM, "key"));
        }

        @Test
        @DisplayName("Uses merchantId from path variables")
        void preHandle_paymentConfirm_usesMerchantIdPathVar() throws Exception {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Map.of("merchantId", "merchant-99"));

            HandlerMethod handler = handlerMethod("paymentConfirmMethod");
            interceptor.preHandle(request, response, handler);

            // Verify merchantId is extracted from path vars and passed as first subject
            ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
            verify(rateLimitService).checkLimit(
                    eq(RateLimitPolicy.PAYMENT_CONFIRM), captor.capture());
            assertThat(captor.getValue()[0]).isEqualTo("merchant-99");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HandlerMethod handlerMethod(String methodName) {
        try {
            Method m = DummyController.class.getMethod(methodName);
            return new HandlerMethod(new DummyController(), m);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No such method: " + methodName, e);
        }
    }

    /** Stub controller methods used to build HandlerMethod instances. */
    @SuppressWarnings("unused")
    static class DummyController {
        public void methodWithoutAnnotation() {}

        @RateLimit(RateLimitPolicy.AUTH_BY_IP)
        public void authByIpMethod() {}

        @RateLimit(RateLimitPolicy.PAYMENT_CONFIRM)
        public void paymentConfirmMethod() {}
    }
}
