package com.firstclub.membership.service.impl;

import com.firstclub.membership.config.SecurityProperties;
import com.firstclub.membership.dto.LoginRequest;
import com.firstclub.membership.dto.LoginResponse;
import com.firstclub.membership.dto.RegisterRequest;
import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.AppAccount;
import com.firstclub.membership.entity.RefreshToken;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.AppAccountRepository;
import com.firstclub.membership.repository.RefreshTokenRepository;
import com.firstclub.membership.security.JwtService;
import com.firstclub.membership.security.LoginAttemptService;
import com.firstclub.membership.service.AuditService;
import com.firstclub.membership.service.AuthService;
import com.firstclub.membership.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AppAccountRepository accountRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityProperties securityProperties;
    private final AuditService auditService;
    private final PlatformTransactionManager txManager;
    private final Clock clock;
    private final LoginAttemptService loginAttemptService;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public UserDTO register(RegisterRequest request) {
        if (accountRepository.existsByUsername(request.getEmail())) {
            throw new MembershipException("An account with this email already exists", "ACCOUNT_EXISTS", HttpStatus.CONFLICT);
        }
        UserDTO created = userService.createUser(UserDTO.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .address(request.getAddress())
                .city(request.getCity())
                .state(request.getState())
                .pincode(request.getPincode())
                .build());

        accountRepository.save(AppAccount.builder()
                .username(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(AppAccount.Role.USER)
                .enabled(true)
                .membershipUserId(created.getId())
                .build());

        return created;
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();
        if (loginAttemptService.isLockedOut(username)) {
            meterRegistry.counter("membership.auth.login", "result", "locked").increment();
            auditService.record(username, "ACCOUNT_LOCKED", "login blocked while locked");
            throw new MembershipException(
                    "Account temporarily locked due to repeated failed logins — try again later",
                    "ACCOUNT_LOCKED", HttpStatus.TOO_MANY_REQUESTS);
        }
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.getPassword()));
        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(username);
            meterRegistry.counter("membership.auth.login", "result", "failure").increment();
            auditService.record(username, "LOGIN_FAILURE", "bad credentials");
            throw e;
        }
        loginAttemptService.recordSuccess(username); // reset on success
        meterRegistry.counter("membership.auth.login", "result", "success").increment();
        auditService.record(username, "LOGIN_SUCCESS", null);

        AppAccount account = accountRepository.findByUsername(username).orElseThrow();
        return issueTokens(account);
    }

    @Override
    @Transactional
    public LoginResponse refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(this::invalidRefresh);
        if (stored.isRevoked()) {
            // Replay of an already-rotated/revoked token implies theft — revoke the whole chain.
            // Must commit in its own transaction so it isn't rolled back by the thrown exception.
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> refreshTokenRepository.revokeAllByUsername(stored.getUsername()));
            throw invalidRefresh();
        }
        if (stored.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw invalidRefresh();
        }
        AppAccount account = accountRepository.findByUsername(stored.getUsername())
                .orElseThrow(this::invalidRefresh);

        // Rotate: revoke the presented token, issue a fresh pair.
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);
        return issueTokens(account);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.findByToken(refreshToken).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    private LoginResponse issueTokens(AppAccount account) {
        String accessToken = jwtService.generateToken(account.getUsername(), account.getRole().name());
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .token(refreshToken)
                .username(account.getUsername())
                .membershipUserId(account.getMembershipUserId())
                .expiresAt(LocalDateTime.now(clock).plusDays(securityProperties.getRefresh().getExpirationDays()))
                .revoked(false)
                .build());

        return LoginResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .role(account.getRole().name())
                .expiresInSeconds(jwtService.getExpirationSeconds())
                .build();
    }

    private MembershipException invalidRefresh() {
        return new MembershipException("Invalid or expired refresh token", "INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED);
    }
}
