package com.firstclub.membership.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.security.JwtTokenProvider;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.TokenBlacklistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = SubscriptionController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class}
)
@DisplayName("SubscriptionController — MockMvc Tests")
class SubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean private MembershipService membershipService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private UserDetailsService userDetailsService;
    // Stub required by IdempotencyFilterConfig which is loaded in all @WebMvcTest contexts.
    @MockitoBean private com.firstclub.platform.idempotency.IdempotencyService idempotencyService;
    // Required by RateLimitInterceptor which is loaded in all @WebMvcTest contexts.
    @MockitoBean private com.firstclub.platform.ratelimit.RateLimitService rateLimitService;

    @Nested
    @DisplayName("GET /api/v1/subscriptions")
    class GetAllSubscriptions {

        @Test
        @DisplayName("returns all subscriptions with no filter")
        void getAllSubscriptions_noFilter() throws Exception {
            Page<SubscriptionDTO> page = new PageImpl<>(List.of(
                SubscriptionDTO.builder().id(1L).status(Subscription.SubscriptionStatus.ACTIVE).build()
            ));
            when(membershipService.getAllSubscriptionsFiltered(isNull(), isNull(), any())).thenReturn(page);

            mockMvc.perform(get("/api/v1/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
        }

        @Test
        @DisplayName("filters by ACTIVE status")
        void getAllSubscriptions_withStatusFilter() throws Exception {
            Page<SubscriptionDTO> page = new PageImpl<>(List.of(
                SubscriptionDTO.builder().id(2L).status(Subscription.SubscriptionStatus.ACTIVE).build()
            ));
            when(membershipService.getAllSubscriptionsFiltered(
                eq(Subscription.SubscriptionStatus.ACTIVE), isNull(), any())).thenReturn(page);

            mockMvc.perform(get("/api/v1/subscriptions?status=ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(2));
        }

        @Test
        @DisplayName("returns 400 for unknown status value")
        void getAllSubscriptions_invalidStatus() throws Exception {
            mockMvc.perform(get("/api/v1/subscriptions?status=INVALID"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when page size exceeds 100")
        void getAllSubscriptions_sizeOverMax() throws Exception {
            mockMvc.perform(get("/api/v1/subscriptions?size=200"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/subscriptions")
    class CreateSubscription {

        @Test
        @DisplayName("returns 201 with created subscription")
        void createSubscription_success() throws Exception {
            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder()
                .userId(1L).planId(2L).build();
            SubscriptionDTO created = SubscriptionDTO.builder()
                .id(10L).userId(1L).planId(2L)
                .status(Subscription.SubscriptionStatus.ACTIVE).build();

            when(membershipService.createSubscription(any())).thenReturn(created);

            mockMvc.perform(post("/api/v1/subscriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/subscriptions/{id}")
    class GetSubscriptionById {

        @Test
        @DisplayName("returns subscription when found")
        void getById_success() throws Exception {
            SubscriptionDTO sub = SubscriptionDTO.builder()
                .id(5L).status(Subscription.SubscriptionStatus.ACTIVE).build();
            when(membershipService.getSubscriptionById(5L)).thenReturn(sub);

            mockMvc.perform(get("/api/v1/subscriptions/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
        }
    }
}
