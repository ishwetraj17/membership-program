package com.firstclub.membership.controller;

import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.membership.dto.TokenRefreshRequestDTO;
import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.security.JwtTokenProvider;
import com.firstclub.membership.service.TokenBlacklistService;
import com.firstclub.membership.service.UserService;
import com.firstclub.platform.ratelimit.RateLimitDecision;
import com.firstclub.platform.ratelimit.RateLimitExceededException;
import com.firstclub.platform.ratelimit.RateLimitPolicy;
import com.firstclub.platform.ratelimit.RateLimitService;
import com.firstclub.platform.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authentication controller: login, registration, token refresh, and logout.
 *
 * POST /api/v1/auth/login    — returns JWT access + refresh token
 * POST /api/v1/auth/register — creates account, returns JWT access + refresh token
 * POST /api/v1/auth/refresh  — exchange a refresh token for a new access token
 * POST /api/v1/auth/logout   — blacklist the current access token
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Login, registration, token refresh, and logout endpoints")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RateLimitService       rateLimitService;

    @PostMapping("/login")
    @RateLimit(RateLimitPolicy.AUTH_BY_IP)
    @Operation(summary = "Authenticate user", description = "Validates credentials and returns JWT access and refresh tokens")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @ApiResponse(responseCode = "429", description = "Too many login attempts — try again in 15 minutes")
    })
    public ResponseEntity<JwtResponseDTO> login(@Valid @RequestBody LoginRequestDTO request) {
        log.info("Login attempt for: {}", request.getEmail());

        // Rate-limit by email address before attempting authentication.
        // The interceptor already checked AUTH_BY_IP; this adds per-email throttling
        // to prevent credential stuffing that rotates source IPs.
        RateLimitDecision emailDecision = rateLimitService.checkLimit(
                RateLimitPolicy.AUTH_BY_EMAIL, request.getEmail().toLowerCase());
        if (!emailDecision.allowed()) {
            throw new RateLimitExceededException(
                    RateLimitPolicy.AUTH_BY_EMAIL,
                    request.getEmail().toLowerCase(),
                    emailDecision.resetAt());
        }

        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        // Reset not needed — sliding window naturally expires stale entries.

        String token = jwtTokenProvider.generateToken(authentication);
        String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);

        // getUserByEmail is the one necessary DB call here: we need userId and name,
        // which are not carried in the UserDetails returned by the AuthenticationManager.
        UserDTO userDetails = userService.getUserByEmail(request.getEmail())
            .orElseThrow(() -> new MembershipException(
                "User not found after successful authentication",
                "INTERNAL_ERROR",
                HttpStatus.INTERNAL_SERVER_ERROR));

        Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

        JwtResponseDTO response = JwtResponseDTO.builder()
            .token(token)
            .refreshToken(refreshToken)
            .type("Bearer")
            .userId(userDetails.getId())
            .email(userDetails.getEmail())
            .name(userDetails.getName())
            .roles(roles)
            .build();

        log.info("Successful login for: {}", request.getEmail());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    @RateLimit(RateLimitPolicy.AUTH_BY_IP)
    @Operation(summary = "Register new user", description = "Creates a new account and returns JWT access and refresh tokens")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Registration successful"),
        @ApiResponse(responseCode = "400", description = "Validation error or email already in use")
    })
    public ResponseEntity<JwtResponseDTO> register(@Valid @RequestBody UserDTO request) {
        log.info("Registration attempt for: {}", request.getEmail());

        UserDTO created = userService.createUser(request);

        // Load UserDetails directly after creation — avoids re-authentication which
        // can fail due to transaction visibility timing or password encoding issues.
        UserDetails userDetails = userDetailsService.loadUserByUsername(created.getEmail());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            userDetails, null, userDetails.getAuthorities()
        );

        String token = jwtTokenProvider.generateToken(authentication);
        String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);

        Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

        JwtResponseDTO response = JwtResponseDTO.builder()
            .token(token)
            .refreshToken(refreshToken)
            .type("Bearer")
            .userId(created.getId())
            .email(created.getEmail())
            .name(created.getName())
            .roles(roles)
            .build();

        log.info("Successful registration for: {}", request.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token", description = "Exchange a valid refresh token for a new short-lived access token")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "New access token issued"),
        @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
    })
    public ResponseEntity<JwtResponseDTO> refresh(@Valid @RequestBody TokenRefreshRequestDTO request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new MembershipException("Invalid or expired refresh token", "INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED);
        }

        if (tokenBlacklistService.isBlacklisted(refreshToken)) {
            throw new MembershipException("Refresh token has been revoked", "REVOKED_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED);
        }

        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
            userDetails, null, userDetails.getAuthorities()
        );

        String newAccessToken = jwtTokenProvider.generateToken(authentication);
        log.info("Access token refreshed for: {}", username);

        Set<String> roles = userDetails.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

        return ResponseEntity.ok(JwtResponseDTO.builder()
            .token(newAccessToken)
            .type("Bearer")
            .email(username)
            .roles(roles)
            .build());
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Revoke the current Bearer access token. The token cannot be used after this call.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Logged out successfully"),
        @ApiResponse(responseCode = "400", description = "No valid token provided")
    })
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(Map.of("error", "No Bearer token provided"));
        }

        String token = header.substring(7);
        if (jwtTokenProvider.validateToken(token)) {
            tokenBlacklistService.blacklist(token);
            log.info("Token blacklisted on logout for: {}", jwtTokenProvider.getUsernameFromToken(token));
        }

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
