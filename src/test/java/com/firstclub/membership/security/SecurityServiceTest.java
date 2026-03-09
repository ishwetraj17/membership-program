package com.firstclub.membership.security;

import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityService Unit Tests")
class SecurityServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private SecurityService securityService;

    @Nested
    @DisplayName("isSameUser()")
    class IsSameUser {

        @Test
        @DisplayName("returns true when authenticated user owns the given userId")
        void returnsTrue_whenOwner() {
            when(authentication.getName()).thenReturn("user@example.com");
            when(userRepository.existsByIdAndEmailIgnoreCaseAndIsDeletedFalse(1L, "user@example.com"))
                .thenReturn(true);

            assertThat(securityService.isSameUser(1L, authentication)).isTrue();
        }

        @Test
        @DisplayName("returns false when the userId belongs to a different user")
        void returnsFalse_whenNotOwner() {
            when(authentication.getName()).thenReturn("other@example.com");
            when(userRepository.existsByIdAndEmailIgnoreCaseAndIsDeletedFalse(1L, "other@example.com"))
                .thenReturn(false);

            assertThat(securityService.isSameUser(1L, authentication)).isFalse();
        }

        @Test
        @DisplayName("returns false when authentication is null")
        void returnsFalse_whenAuthNull() {
            assertThat(securityService.isSameUser(1L, null)).isFalse();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("returns false when userId is null")
        void returnsFalse_whenUserIdNull() {
            assertThat(securityService.isSameUser(null, authentication)).isFalse();
            verifyNoInteractions(userRepository);
        }
    }

    @Nested
    @DisplayName("isSubscriptionOwner()")
    class IsSubscriptionOwner {

        @Test
        @DisplayName("returns true when subscription belongs to authenticated principal")
        void returnsTrue_whenOwner() {
            when(authentication.getName()).thenReturn("user@example.com");
            when(subscriptionRepository.existsByIdAndUserEmail(42L, "user@example.com"))
                .thenReturn(true);

            assertThat(securityService.isSubscriptionOwner(42L, authentication)).isTrue();
        }

        @Test
        @DisplayName("returns false when subscription belongs to someone else")
        void returnsFalse_whenNotOwner() {
            when(authentication.getName()).thenReturn("other@example.com");
            when(subscriptionRepository.existsByIdAndUserEmail(42L, "other@example.com"))
                .thenReturn(false);

            assertThat(securityService.isSubscriptionOwner(42L, authentication)).isFalse();
        }

        @Test
        @DisplayName("returns false when authentication is null")
        void returnsFalse_whenAuthNull() {
            assertThat(securityService.isSubscriptionOwner(42L, null)).isFalse();
            verifyNoInteractions(subscriptionRepository);
        }

        @Test
        @DisplayName("returns false when subscriptionId is null")
        void returnsFalse_whenSubIdNull() {
            assertThat(securityService.isSubscriptionOwner(null, authentication)).isFalse();
            verifyNoInteractions(subscriptionRepository);
        }
    }
}
