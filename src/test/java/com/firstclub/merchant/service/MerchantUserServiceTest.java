package com.firstclub.merchant.service;

import com.firstclub.membership.entity.User;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.merchant.dto.MerchantUserCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantUserResponseDTO;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantUser;
import com.firstclub.merchant.entity.MerchantUserRole;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.mapper.MerchantUserMapper;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.merchant.repository.MerchantUserRepository;
import com.firstclub.merchant.service.impl.MerchantUserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantUserServiceImpl Unit Tests")
class MerchantUserServiceTest {

    @Mock
    private MerchantAccountRepository merchantAccountRepository;

    @Mock
    private MerchantUserRepository merchantUserRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MerchantUserMapper merchantUserMapper;

    @InjectMocks
    private MerchantUserServiceImpl merchantUserService;

    private MerchantAccount merchant;
    private User user;
    private MerchantUser ownerAssignment;
    private MerchantUserCreateRequestDTO addOwnerRequest;
    private MerchantUserResponseDTO ownerResponseDTO;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(10L)
                .merchantCode("TEST_MERCHANT")
                .build();

        user = new User();
        user.setId(20L);
        user.setEmail("owner@test.com");
        user.setName("Test Owner");

        ownerAssignment = MerchantUser.builder()
                .id(1L)
                .merchant(merchant)
                .user(user)
                .role(MerchantUserRole.OWNER)
                .build();

        addOwnerRequest = new MerchantUserCreateRequestDTO();
        addOwnerRequest.setUserId(20L);
        addOwnerRequest.setRole(MerchantUserRole.OWNER);

        ownerResponseDTO = new MerchantUserResponseDTO();
        ownerResponseDTO.setId(1L);
        ownerResponseDTO.setMerchantId(10L);
        ownerResponseDTO.setUserId(20L);
        ownerResponseDTO.setRole(MerchantUserRole.OWNER);
    }

    @Nested
    @DisplayName("addUserToMerchant")
    class AddUserTests {

        @Test
        @DisplayName("Should add user successfully")
        void shouldAddUserSuccessfully() {
            when(merchantAccountRepository.findById(10L)).thenReturn(Optional.of(merchant));
            when(userRepository.findById(20L)).thenReturn(Optional.of(user));
            when(merchantUserRepository.existsByMerchantIdAndUserId(10L, 20L)).thenReturn(false);
            when(merchantUserRepository.save(any(MerchantUser.class))).thenReturn(ownerAssignment);
            when(merchantUserMapper.toResponseDTO(ownerAssignment)).thenReturn(ownerResponseDTO);

            MerchantUserResponseDTO result = merchantUserService.addUserToMerchant(10L, addOwnerRequest);

            assertThat(result).isNotNull();
            assertThat(result.getMerchantId()).isEqualTo(10L);
            assertThat(result.getUserId()).isEqualTo(20L);
            assertThat(result.getRole()).isEqualTo(MerchantUserRole.OWNER);
            verify(merchantUserRepository).save(any(MerchantUser.class));
        }

        @Test
        @DisplayName("Should reject duplicate user assignment")
        void shouldRejectDuplicateUserAssignment() {
            when(merchantAccountRepository.findById(10L)).thenReturn(Optional.of(merchant));
            when(userRepository.findById(20L)).thenReturn(Optional.of(user));
            when(merchantUserRepository.existsByMerchantIdAndUserId(10L, 20L)).thenReturn(true);

            assertThatThrownBy(() -> merchantUserService.addUserToMerchant(10L, addOwnerRequest))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("DUPLICATE_MERCHANT_USER");

            verify(merchantUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw when merchant not found")
        void shouldThrowWhenMerchantNotFound() {
            when(merchantAccountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> merchantUserService.addUserToMerchant(999L, addOwnerRequest))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("MERCHANT_NOT_FOUND");
        }

        @Test
        @DisplayName("Should throw when user not found")
        void shouldThrowWhenUserNotFound() {
            when(merchantAccountRepository.findById(10L)).thenReturn(Optional.of(merchant));
            when(userRepository.findById(20L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> merchantUserService.addUserToMerchant(10L, addOwnerRequest))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("USER_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("removeUserFromMerchant")
    class RemoveUserTests {

        @Test
        @DisplayName("Should remove non-owner user successfully")
        void shouldRemoveNonOwnerSuccessfully() {
            MerchantUser supportAssignment = MerchantUser.builder()
                    .id(2L)
                    .merchant(merchant)
                    .user(user)
                    .role(MerchantUserRole.SUPPORT)
                    .build();

            when(merchantUserRepository.findByMerchantIdAndUserId(10L, 20L))
                    .thenReturn(Optional.of(supportAssignment));
            doNothing().when(merchantUserRepository).deleteByMerchantIdAndUserId(10L, 20L);

            assertThatCode(() -> merchantUserService.removeUserFromMerchant(10L, 20L))
                    .doesNotThrowAnyException();

            verify(merchantUserRepository).deleteByMerchantIdAndUserId(10L, 20L);
        }

        @Test
        @DisplayName("Should reject removal of last OWNER")
        void shouldRejectRemovalOfLastOwner() {
            when(merchantUserRepository.findByMerchantIdAndUserId(10L, 20L))
                    .thenReturn(Optional.of(ownerAssignment));
            when(merchantUserRepository.countByMerchantIdAndRole(10L, MerchantUserRole.OWNER))
                    .thenReturn(1L);

            assertThatThrownBy(() -> merchantUserService.removeUserFromMerchant(10L, 20L))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("CANNOT_REMOVE_LAST_OWNER");

            verify(merchantUserRepository, never()).deleteByMerchantIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("Should allow removal of one OWNER when multiple owners exist")
        void shouldAllowRemovalWhenMultipleOwners() {
            when(merchantUserRepository.findByMerchantIdAndUserId(10L, 20L))
                    .thenReturn(Optional.of(ownerAssignment));
            when(merchantUserRepository.countByMerchantIdAndRole(10L, MerchantUserRole.OWNER))
                    .thenReturn(2L);
            doNothing().when(merchantUserRepository).deleteByMerchantIdAndUserId(10L, 20L);

            assertThatCode(() -> merchantUserService.removeUserFromMerchant(10L, 20L))
                    .doesNotThrowAnyException();

            verify(merchantUserRepository).deleteByMerchantIdAndUserId(10L, 20L);
        }

        @Test
        @DisplayName("Should throw when user not in merchant")
        void shouldThrowWhenUserNotInMerchant() {
            when(merchantUserRepository.findByMerchantIdAndUserId(10L, 99L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> merchantUserService.removeUserFromMerchant(10L, 99L))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("USER_NOT_IN_MERCHANT");
        }
    }

    @Nested
    @DisplayName("listMerchantUsers")
    class ListUsersTests {

        @Test
        @DisplayName("Should return empty list when no users assigned")
        void shouldReturnEmptyListWhenNoUsers() {
            when(merchantAccountRepository.existsById(10L)).thenReturn(true);
            when(merchantUserRepository.findByMerchantId(10L)).thenReturn(List.of());

            List<MerchantUserResponseDTO> result = merchantUserService.listMerchantUsers(10L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return users for valid merchant")
        void shouldReturnUsersForValidMerchant() {
            when(merchantAccountRepository.existsById(10L)).thenReturn(true);
            when(merchantUserRepository.findByMerchantId(10L)).thenReturn(List.of(ownerAssignment));
            when(merchantUserMapper.toResponseDTO(ownerAssignment)).thenReturn(ownerResponseDTO);

            List<MerchantUserResponseDTO> result = merchantUserService.listMerchantUsers(10L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRole()).isEqualTo(MerchantUserRole.OWNER);
        }

        @Test
        @DisplayName("Should throw when merchant not found")
        void shouldThrowWhenMerchantNotFound() {
            when(merchantAccountRepository.existsById(999L)).thenReturn(false);

            assertThatThrownBy(() -> merchantUserService.listMerchantUsers(999L))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("MERCHANT_NOT_FOUND");
        }
    }
}
