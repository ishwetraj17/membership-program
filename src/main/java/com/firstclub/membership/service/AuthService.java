package com.firstclub.membership.service;

import com.firstclub.membership.dto.LoginRequest;
import com.firstclub.membership.dto.LoginResponse;
import com.firstclub.membership.dto.RegisterRequest;
import com.firstclub.membership.dto.UserDTO;

/**
 * Authentication use-cases (registration, login/token issuance, refresh, logout), keeping
 * account/credential persistence out of the controller layer.
 */
public interface AuthService {

    UserDTO register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    /** Exchange a valid refresh token for a new access token (rotating the refresh token). */
    LoginResponse refresh(String refreshToken);

    /** Revoke a refresh token (logout); idempotent. */
    void logout(String refreshToken);
}
