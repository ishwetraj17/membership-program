package com.firstclub.membership.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.dto.PatchUserDTO;
import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.security.JwtTokenProvider;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.TokenBlacklistService;
import com.firstclub.membership.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = UserController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class}
)
@DisplayName("UserController — MockMvc Tests")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean private UserService userService;
    @MockitoBean private MembershipService membershipService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private UserDetailsService userDetailsService;
    // Stub required by IdempotencyFilterConfig which is loaded in all @WebMvcTest contexts.
    @MockitoBean private com.firstclub.platform.idempotency.IdempotencyService idempotencyService;
    // Required by RateLimitInterceptor which is loaded in all @WebMvcTest contexts.
    @MockitoBean private com.firstclub.platform.ratelimit.RateLimitService rateLimitService;

    private UserDTO fullUser(Long id, String name, String email) {
        return UserDTO.builder().id(id).name(name).email(email)
            .phoneNumber("9876543210").address("1 Test St")
            .city("Bangalore").state("Karnataka").pincode("560001")
            .status(User.UserStatus.ACTIVE).build();
    }

    @Nested
    @DisplayName("POST /api/v1/users")
    class CreateUser {

        @Test
        @DisplayName("returns 201 when user data is valid")
        void createUser_success() throws Exception {
            UserDTO input = fullUser(null, "Test User", "test@example.com");
            input.setPassword("Test@1234");
            UserDTO saved = fullUser(1L, "Test User", "test@example.com");

            when(userService.createUser(any())).thenReturn(saved);

            mockMvc.perform(post("/api/v1/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("test@example.com"));
        }

        @Test
        @DisplayName("returns 400 when required fields are missing")
        void createUser_missingFields() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"test@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/users/{id}")
    class GetUserById {

        @Test
        @DisplayName("returns 200 when user is found")
        void getUser_found() throws Exception {
            UserDTO user = fullUser(1L, "A", "a@test.com");
            when(userService.getUserById(1L)).thenReturn(Optional.of(user));

            mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
        }

        @Test
        @DisplayName("returns 404 when user is not found")
        void getUser_notFound() throws Exception {
            when(userService.getUserById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/users/99"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/users")
    class GetAllUsers {

        @Test
        @DisplayName("returns 200 with paged user list")
        void getAllUsers_success() throws Exception {
            Page<UserDTO> page = new PageImpl<>(List.of(fullUser(1L, "A", "a@test.com")));
            when(userService.getAllUsersPaged(any())).thenReturn(page);

            mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
        }

        @Test
        @DisplayName("returns 400 when page size exceeds 100")
        void getAllUsers_sizeOverMax() throws Exception {
            mockMvc.perform(get("/api/v1/users?size=200"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/users/{id}")
    class PartialUpdateUser {

        @Test
        @DisplayName("returns 200 when patch is valid")
        void patch_success() throws Exception {
            UserDTO existing = fullUser(1L, "Old Name", "u@test.com");
            UserDTO updated  = fullUser(1L, "New Name", "u@test.com");

            when(userService.getUserById(1L)).thenReturn(Optional.of(existing));
            when(userService.updateUser(eq(1L), any())).thenReturn(updated);

            PatchUserDTO patch = PatchUserDTO.builder().name("New Name").build();

            mockMvc.perform(patch("/api/v1/users/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
        }

        @Test
        @DisplayName("returns 400 when patch contains invalid phone number")
        void patch_invalidPhone() throws Exception {
            PatchUserDTO patch = PatchUserDTO.builder().phoneNumber("123").build();

            mockMvc.perform(patch("/api/v1/users/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(patch)))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/users/{id}")
    class DeleteUser {

        @Test
        @DisplayName("returns 204 on successful deletion")
        void deleteUser_success() throws Exception {
            doNothing().when(userService).deleteUser(1L);

            mockMvc.perform(delete("/api/v1/users/1"))
                .andExpect(status().isNoContent());
        }
    }
}
