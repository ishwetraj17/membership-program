package com.firstclub.membership.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.security.JwtTokenProvider;
import com.firstclub.membership.service.TokenBlacklistService;
import com.firstclub.membership.service.UserService;
import com.firstclub.platform.ratelimit.RateLimitDecision;
import com.firstclub.platform.ratelimit.RateLimitPolicy;
import com.firstclub.platform.ratelimit.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = AuthController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class}
)
@DisplayName("AuthController — MockMvc Tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean private AuthenticationManager authenticationManager;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private UserService userService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private RateLimitService rateLimitService;
    // Stub required by IdempotencyFilterConfig which is loaded in all @WebMvcTest contexts.
    @MockitoBean private com.firstclub.platform.idempotency.IdempotencyService idempotencyService;

    @BeforeEach
    void stubDefaultRateLimit() {
        // Default: allow all rate-limit checks (interceptor + controller)
        when(rateLimitService.checkLimit(any(RateLimitPolicy.class), any()))
                .thenReturn(RateLimitDecision.permissive(RateLimitPolicy.AUTH_BY_IP, "test-key"));
    }

    private LoginRequestDTO loginRequest(String email, String password) {
        return LoginRequestDTO.builder().email(email).password(password).build();
    }

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class Login {

        @Test
        @DisplayName("returns 200 with JWT on valid credentials")
        void login_success() throws Exception {
            var auth = new UsernamePasswordAuthenticationToken("user@test.com", null, List.of());
            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(jwtTokenProvider.generateToken(any())).thenReturn("access-token");
            when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-token");
            when(userService.getUserByEmail("user@test.com")).thenReturn(Optional.of(
                UserDTO.builder().id(1L).email("user@test.com").name("Test User").build()
            ));

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginRequest("user@test.com", "Pass@123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.email").value("user@test.com"));

            verify(rateLimitService).checkLimit(eq(RateLimitPolicy.AUTH_BY_EMAIL), eq("user@test.com"));
        }

        @Test
        @DisplayName("returns 401 when credentials are wrong")
        void login_badCredentials() throws Exception {
            when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginRequest("user@test.com", "wrong"))))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("returns 429 when rate limiter rejects the request")
        void login_tooManyAttempts() throws Exception {
            // Email-level rate limit → deny
            Instant resetAt = Instant.now().plusSeconds(900);
            when(rateLimitService.checkLimit(eq(RateLimitPolicy.AUTH_BY_EMAIL), any()))
                    .thenReturn(RateLimitDecision.deny(RateLimitPolicy.AUTH_BY_EMAIL, "test-key", 5, resetAt));

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginRequest("user@test.com", "Pass@123"))))
                .andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("returns 400 when email is missing")
        void login_missingEmail() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"Pass@123\"}"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class Register {

        @Test
        @DisplayName("returns 201 with JWT on successful registration")
        void register_success() throws Exception {
            UserDTO input = UserDTO.builder()
                .name("New User").email("new@test.com").password("Pass@123X")
                .phoneNumber("9876543210").address("1 Test St")
                .city("Mumbai").state("Maharashtra").pincode("400001")
                .build();
            UserDTO created = UserDTO.builder()
                .id(5L).name("New User").email("new@test.com").build();

            when(userService.createUser(any())).thenReturn(created);

            var userDetails = org.springframework.security.core.userdetails.User
                .withUsername("new@test.com").password("encoded").roles("USER").build();
            when(userDetailsService.loadUserByUsername("new@test.com")).thenReturn(userDetails);
            when(jwtTokenProvider.generateToken(any())).thenReturn("new-token");
            when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("new-refresh");

            mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("new-token"))
                .andExpect(jsonPath("$.email").value("new@test.com"));
        }
    }
}

