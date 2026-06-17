package com.firstclub.membership.repository;

import com.firstclub.membership.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    /** Revoke every refresh token for a user — the response to a detected token replay. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.username = :username AND t.revoked = false")
    void revokeAllByUsername(@Param("username") String username);
}
