package com.firstclub.membership.controller;

import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.membership.dto.TokenRefreshRequestDTO;
import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.security.JwtTokenProvider;
import com.firstclub.membership.service.TokenBlacklistService;
import com.firstclub.membership.service.UserService;
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

    @PostMapping("/login")
    @Operation(summary = "Authenticate user", description = "Validates credentials and returns JWT access and refresh tokens")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<JwtResponseDTO> login(@Valid @RequestBody LoginRequestDTO request) {
        log.info("Login attempt for: {}", request.getEmail());

        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        String token = jwtTokenProvider.generateToken(authentication);
        String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);

        var userDetails = userService.getUserByEmail(request.getEmail())
            .orElseThrow(() -> new RuntimeException("User not found after successful authentication"));

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
    public ResponseEntity<?> refresh(@Valid @RequestBody TokenRefreshRequestDTO request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid or expired refresh token"));
        }

        if (tokenBlacklistService.isBlacklisted(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Refresh token has been revoked"));
        }

        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
            userDetails, null, userDetails.getAuthorities()
        );

        String newAccessToken = jwtTokenProvider.generateToken(authentication);
        log.info("Access token refreshed for: {}", username);

        return ResponseEntity.ok(Map.of("token", newAccessToken, "type", "Bearer"));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Revoke the current Bearer access token. The token cannot be used after this call.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Logged out successfully"),
        @ApiResponse(responseCode = "400", description = "No valid token provided")
    })
    public ResponseEntity<?> logout(HttpServletRequest request) {
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

