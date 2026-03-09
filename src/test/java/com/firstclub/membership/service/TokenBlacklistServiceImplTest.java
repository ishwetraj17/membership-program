package com.firstclub.membership.service;

import com.firstclub.membership.security.JwtTokenProvider;
import com.firstclub.membership.service.impl.TokenBlacklistServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenBlacklistServiceImpl Unit Tests")
class TokenBlacklistServiceImplTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private TokenBlacklistServiceImpl tokenBlacklistService;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String ANOTHER_TOKEN = "another.jwt.token";

    @Nested
    @DisplayName("blacklist() and isBlacklisted()")
    class BlacklistTests {

        @Test
        @DisplayName("Should return true after blacklisting a token")
        void shouldReturnTrueAfterBlacklisting() {
            Date futureExpiry = Date.from(Instant.now().plusSeconds(3600));
            when(jwtTokenProvider.getExpirationFromToken(VALID_TOKEN)).thenReturn(futureExpiry);

            tokenBlacklistService.blacklist(VALID_TOKEN);

            assertThat(tokenBlacklistService.isBlacklisted(VALID_TOKEN)).isTrue();
        }

        @Test
        @DisplayName("Should return false for a token that has not been blacklisted")
        void shouldReturnFalseForNonBlacklistedToken() {
            assertThat(tokenBlacklistService.isBlacklisted(VALID_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("Should handle malformed token gracefully — falls back to 24-hour TTL")
        void shouldFallBackTo24HourTTLOnParseFailure() {
            when(jwtTokenProvider.getExpirationFromToken(VALID_TOKEN))
                    .thenThrow(new RuntimeException("malformed token"));

            // Should not throw
            assertThatCode(() -> tokenBlacklistService.blacklist(VALID_TOKEN))
                    .doesNotThrowAnyException();

            // Token must still be considered blacklisted after the fallback path
            assertThat(tokenBlacklistService.isBlacklisted(VALID_TOKEN)).isTrue();
        }

        @Test
        @DisplayName("Blacklisting one token should not affect other tokens")
        void shouldNotAffectOtherTokens() {
            Date futureExpiry = Date.from(Instant.now().plusSeconds(3600));
            when(jwtTokenProvider.getExpirationFromToken(VALID_TOKEN)).thenReturn(futureExpiry);

            tokenBlacklistService.blacklist(VALID_TOKEN);

            assertThat(tokenBlacklistService.isBlacklisted(ANOTHER_TOKEN)).isFalse();
        }
    }

    @Nested
    @DisplayName("evictExpired()")
    class EvictExpiredTests {

        @Test
        @DisplayName("Should remove tokens whose expiry is in the past")
        void shouldRemoveExpiredTokens() {
            // Token with expiry 2 seconds ago — should be evicted
            Date pastExpiry = Date.from(Instant.now().minusSeconds(2));
            when(jwtTokenProvider.getExpirationFromToken(VALID_TOKEN)).thenReturn(pastExpiry);
            tokenBlacklistService.blacklist(VALID_TOKEN);

            tokenBlacklistService.evictExpired();

            assertThat(tokenBlacklistService.isBlacklisted(VALID_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("Should retain tokens whose expiry is in the future")
        void shouldRetainValidTokens() {
            Date futureExpiry = Date.from(Instant.now().plusSeconds(3600));
            when(jwtTokenProvider.getExpirationFromToken(VALID_TOKEN)).thenReturn(futureExpiry);
            tokenBlacklistService.blacklist(VALID_TOKEN);

            tokenBlacklistService.evictExpired();

            assertThat(tokenBlacklistService.isBlacklisted(VALID_TOKEN)).isTrue();
        }

        @Test
        @DisplayName("Should remove expired tokens while keeping valid ones")
        void shouldRemoveExpiredAndRetainValid() {
            Date pastExpiry   = Date.from(Instant.now().minusSeconds(10));
            Date futureExpiry = Date.from(Instant.now().plusSeconds(3600));

            when(jwtTokenProvider.getExpirationFromToken(VALID_TOKEN)).thenReturn(pastExpiry);
            when(jwtTokenProvider.getExpirationFromToken(ANOTHER_TOKEN)).thenReturn(futureExpiry);

            tokenBlacklistService.blacklist(VALID_TOKEN);
            tokenBlacklistService.blacklist(ANOTHER_TOKEN);

            tokenBlacklistService.evictExpired();

            assertThat(tokenBlacklistService.isBlacklisted(VALID_TOKEN)).isFalse();
            assertThat(tokenBlacklistService.isBlacklisted(ANOTHER_TOKEN)).isTrue();
        }

        @Test
        @DisplayName("evictExpired should not throw when the blacklist is empty")
        void shouldNotThrowOnEmptyBlacklist() {
            assertThatCode(() -> tokenBlacklistService.evictExpired())
                    .doesNotThrowAnyException();
        }
    }
}
