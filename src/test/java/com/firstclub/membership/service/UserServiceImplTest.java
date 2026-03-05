package com.firstclub.membership.service;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.mapper.UserMapper;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl Unit Tests")
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private UserDTO sampleDTO;
    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleDTO = UserDTO.builder()
                .name("Karan Singh")
                .email("karan@test.com")
                .password("Secret@123")
                .phoneNumber("9876543210")
                .address("12 HSR Layout")
                .city("Bangalore")
                .state("Karnataka")
                .pincode("560102")
                .build();

        sampleUser = User.builder()
                .id(1L)
                .name("Karan Singh")
                .email("karan@test.com")
                .password("$2a$10$encodedPassword")
                .phoneNumber("9876543210")
                .address("12 HSR Layout")
                .city("Bangalore")
                .state("Karnataka")
                .pincode("560102")
                .status(User.UserStatus.ACTIVE)
                .roles(Set.of("ROLE_USER"))
                .isDeleted(false)
                .build();
    }

    @Nested
    @DisplayName("createUser()")
    class CreateUserTests {

        @Test
        @DisplayName("Should create a user when email does not exist")
        void shouldCreateUserSuccessfully() {
            when(userRepository.existsByEmailAndIsDeletedFalse(sampleDTO.getEmail())).thenReturn(false);
            when(userMapper.toEntity(sampleDTO)).thenReturn(sampleUser);
            when(passwordEncoder.encode(any())).thenReturn("$2a$10$encodedPassword");
            when(userRepository.save(any(User.class))).thenReturn(sampleUser);
            when(userMapper.toDTO(sampleUser)).thenReturn(sampleDTO);

            UserDTO result = userService.createUser(sampleDTO);

            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo("karan@test.com");
            assertThat(result.getName()).isEqualTo("Karan Singh");
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("Should throw MembershipException when email already exists")
        void shouldThrowWhenEmailAlreadyExists() {
            when(userRepository.existsByEmailAndIsDeletedFalse(sampleDTO.getEmail())).thenReturn(true);

            assertThatThrownBy(() -> userService.createUser(sampleDTO))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Email already exists");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getUserById()")
    class GetUserByIdTests {

        @Test
        @DisplayName("Should return user DTO when user exists and is not deleted")
        void shouldReturnUserWhenFound() {
            when(userRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(sampleUser));
            when(userMapper.toDTO(sampleUser)).thenReturn(sampleDTO);

            Optional<UserDTO> result = userService.getUserById(1L);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("Should return empty Optional for soft-deleted or missing user")
        void shouldReturnEmptyForDeletedUser() {
            when(userRepository.findByIdAndIsDeletedFalse(99L)).thenReturn(Optional.empty());

            Optional<UserDTO> result = userService.getUserById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAllUsersPaged()")
    class GetAllUsersPagedTests {

        @Test
        @DisplayName("Should return a page of non-deleted users")
        void shouldReturnPagedUsers() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<User> userPage = new PageImpl<>(List.of(sampleUser), pageable, 1);
            when(userRepository.findByIsDeletedFalse(pageable)).thenReturn(userPage);
            when(userMapper.toDTO(sampleUser)).thenReturn(sampleDTO);

            Page<UserDTO> result = userService.getAllUsersPaged(pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getEmail()).isEqualTo("karan@test.com");
        }
    }

    @Nested
    @DisplayName("deleteUser()")
    class DeleteUserTests {

        @Test
        @DisplayName("Should soft-delete user when no active subscriptions exist")
        void shouldSoftDeleteUserSuccessfully() {
            when(userRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(sampleUser));
            when(subscriptionRepository.hasActiveSubscriptions(eq(sampleUser), any(LocalDateTime.class)))
                    .thenReturn(false);
            when(userRepository.save(any(User.class))).thenReturn(sampleUser);

            userService.deleteUser(1L);

            verify(userRepository).save(argThat(u -> u.getIsDeleted()));
            verify(userRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Should throw when user has active subscriptions")
        void shouldThrowWhenUserHasActiveSubscriptions() {
            when(userRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(sampleUser));
            when(subscriptionRepository.hasActiveSubscriptions(eq(sampleUser), any(LocalDateTime.class)))
                    .thenReturn(true);

            assertThatThrownBy(() -> userService.deleteUser(1L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("active subscriptions");

            verify(userRepository, never()).save(any());
            verify(userRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Should throw when user not found")
        void shouldThrowWhenUserNotFound() {
            when(userRepository.findByIdAndIsDeletedFalse(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.deleteUser(99L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("User not found");
        }
    }

    @Nested
    @DisplayName("updateUser()")
    class UpdateUserTests {

        @Test
        @DisplayName("Should update user fields successfully")
        void shouldUpdateUserSuccessfully() {
            UserDTO updateDTO = UserDTO.builder()
                    .name("Karan Updated")
                    .email("karan@test.com")
                    .phoneNumber("9123456789")
                    .address("New Address")
                    .city("Mumbai")
                    .state("Maharashtra")
                    .pincode("400001")
                    .build();

            UserDTO updatedDTO = UserDTO.builder()
                    .id(1L)
                    .name("Karan Updated")
                    .email("karan@test.com")
                    .city("Mumbai")
                    .build();

            when(userRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(sampleUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userMapper.toDTO(any(User.class))).thenReturn(updatedDTO);

            UserDTO result = userService.updateUser(1L, updateDTO);

            assertThat(result.getName()).isEqualTo("Karan Updated");
            assertThat(result.getCity()).isEqualTo("Mumbai");
        }

        @Test
        @DisplayName("Should throw when updating email to one already taken")
        void shouldThrowWhenNewEmailAlreadyTaken() {
            UserDTO updateDTO = UserDTO.builder()
                    .name(sampleDTO.getName()).email("taken@test.com")
                    .phoneNumber(sampleDTO.getPhoneNumber()).address(sampleDTO.getAddress())
                    .city(sampleDTO.getCity()).state(sampleDTO.getState()).pincode(sampleDTO.getPincode())
                    .build();

            when(userRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(sampleUser));
            when(userRepository.existsByEmailAndIsDeletedFalse("taken@test.com")).thenReturn(true);

            assertThatThrownBy(() -> userService.updateUser(1L, updateDTO))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Email already exists");
        }
    }
}
