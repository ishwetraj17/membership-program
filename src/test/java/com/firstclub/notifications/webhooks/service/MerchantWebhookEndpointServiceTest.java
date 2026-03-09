package com.firstclub.notifications.webhooks.service;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointCreateRequestDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointResponseDTO;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookEndpoint;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookEndpointRepository;
import com.firstclub.notifications.webhooks.service.impl.MerchantWebhookEndpointServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantWebhookEndpointService Unit Tests")
class MerchantWebhookEndpointServiceTest {

    @Mock
    private MerchantWebhookEndpointRepository endpointRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MerchantWebhookEndpointServiceImpl endpointService;

    private static final Long MERCHANT_ID  = 1L;
    private static final Long ENDPOINT_ID  = 10L;

    private MerchantWebhookEndpointCreateRequestDTO validRequest;

    @BeforeEach
    void setUp() {
        validRequest = MerchantWebhookEndpointCreateRequestDTO.builder()
                .url("https://example.com/webhook")
                .subscribedEventsJson("[\"invoice.paid\",\"payment.failed\"]")
                .active(true)
                .build();
    }

    // ── createEndpoint ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createEndpoint")
    class CreateEndpoint {

        @Test
        @DisplayName("valid request without secret → secret generated, DTO returned")
        void createEndpoint_noSecret_secretGenerated() {
            when(endpointRepository.save(any(MerchantWebhookEndpoint.class)))
                    .thenAnswer(inv -> {
                        MerchantWebhookEndpoint ep = inv.getArgument(0);
                        ep.setId(ENDPOINT_ID);
                        ep.setCreatedAt(LocalDateTime.now());
                        ep.setUpdatedAt(LocalDateTime.now());
                        return ep;
                    });

            MerchantWebhookEndpointResponseDTO result =
                    endpointService.createEndpoint(MERCHANT_ID, validRequest);

            assertThat(result.getId()).isEqualTo(ENDPOINT_ID);
            assertThat(result.getMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(result.getUrl()).isEqualTo("https://example.com/webhook");
            assertThat(result.isActive()).isTrue();

            // Secret must NOT appear in response
            assertThat(result).doesNotHaveToString("secret");

            // Saved entity must have a generated secret (64-char hex)
            var cap = org.mockito.ArgumentCaptor.forClass(MerchantWebhookEndpoint.class);
            verify(endpointRepository).save(cap.capture());
            assertThat(cap.getValue().getSecret()).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("valid request with explicit secret → uses provided secret")
        void createEndpoint_withSecret_usesProvidedSecret() {
            when(endpointRepository.save(any())).thenAnswer(inv -> {
                MerchantWebhookEndpoint ep = inv.getArgument(0);
                ep.setId(ENDPOINT_ID);
                return ep;
            });
            MerchantWebhookEndpointCreateRequestDTO req = MerchantWebhookEndpointCreateRequestDTO
                    .builder()
                    .url("https://example.com/hook")
                    .secret("my-custom-secret")
                    .subscribedEventsJson("[\"invoice.paid\"]")
                    .build();

            endpointService.createEndpoint(MERCHANT_ID, req);

            var cap = org.mockito.ArgumentCaptor.forClass(MerchantWebhookEndpoint.class);
            verify(endpointRepository).save(cap.capture());
            assertThat(cap.getValue().getSecret()).isEqualTo("my-custom-secret");
        }

        @Test
        @DisplayName("blank URL → 422 MembershipException INVALID_WEBHOOK_URL")
        void createEndpoint_blankUrl_throws422() {
            MerchantWebhookEndpointCreateRequestDTO bad = MerchantWebhookEndpointCreateRequestDTO
                    .builder()
                    .url("")
                    .subscribedEventsJson("[\"invoice.paid\"]")
                    .build();

            assertThatThrownBy(() -> endpointService.createEndpoint(MERCHANT_ID, bad))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("URL")
                    .extracting(e -> ((MembershipException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            verifyNoInteractions(endpointRepository);
        }

        @Test
        @DisplayName("URL without http/https prefix → 422 INVALID_WEBHOOK_URL")
        void createEndpoint_relativeUrl_throws422() {
            MerchantWebhookEndpointCreateRequestDTO bad = MerchantWebhookEndpointCreateRequestDTO
                    .builder()
                    .url("example.com/hook")
                    .subscribedEventsJson("[\"invoice.paid\"]")
                    .build();

            assertThatThrownBy(() -> endpointService.createEndpoint(MERCHANT_ID, bad))
                    .isInstanceOf(MembershipException.class)
                    .extracting(e -> ((MembershipException) e).getErrorCode())
                    .isEqualTo("INVALID_WEBHOOK_URL");
        }

        @Test
        @DisplayName("invalid subscribedEventsJson → 422 INVALID_SUBSCRIBED_EVENTS")
        void createEndpoint_invalidEventsJson_throws422() {
            MerchantWebhookEndpointCreateRequestDTO bad = MerchantWebhookEndpointCreateRequestDTO
                    .builder()
                    .url("https://example.com/hook")
                    .subscribedEventsJson("not-json")
                    .build();

            assertThatThrownBy(() -> endpointService.createEndpoint(MERCHANT_ID, bad))
                    .isInstanceOf(MembershipException.class)
                    .extracting(e -> ((MembershipException) e).getErrorCode())
                    .isEqualTo("INVALID_SUBSCRIBED_EVENTS");
        }

        @Test
        @DisplayName("empty events array → 422 INVALID_SUBSCRIBED_EVENTS")
        void createEndpoint_emptyEventsArray_throws422() {
            MerchantWebhookEndpointCreateRequestDTO bad = MerchantWebhookEndpointCreateRequestDTO
                    .builder()
                    .url("https://example.com/hook")
                    .subscribedEventsJson("[]")
                    .build();

            assertThatThrownBy(() -> endpointService.createEndpoint(MERCHANT_ID, bad))
                    .isInstanceOf(MembershipException.class)
                    .extracting(e -> ((MembershipException) e).getErrorCode())
                    .isEqualTo("INVALID_SUBSCRIBED_EVENTS");
        }
    }

    // ── listEndpoints ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("listEndpoints → returns mapped DTOs for all endpoints")
    void listEndpoints_returnsMappedDtos() {
        MerchantWebhookEndpoint ep1 = endpoint(1L, "https://a.com/hook", true);
        MerchantWebhookEndpoint ep2 = endpoint(2L, "https://b.com/hook", false);
        when(endpointRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(ep1, ep2));

        List<MerchantWebhookEndpointResponseDTO> result = endpointService.listEndpoints(MERCHANT_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(1).isActive()).isFalse();
    }

    // ── updateEndpoint ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateEndpoint")
    class UpdateEndpoint {

        @Test
        @DisplayName("owned endpoint → URL and events updated, secret retained when blank")
        void updateEndpoint_success_updatesFields() {
            MerchantWebhookEndpoint existing = endpoint(ENDPOINT_ID, "https://old.com/hook", true);
            existing.setSecret("original-secret");
            when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                    .thenReturn(Optional.of(existing));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MerchantWebhookEndpointCreateRequestDTO update = MerchantWebhookEndpointCreateRequestDTO
                    .builder()
                    .url("https://new.com/hook")
                    .secret("")  // blank → keep existing secret
                    .subscribedEventsJson("[\"subscription.activated\"]")
                    .active(false)
                    .build();

            MerchantWebhookEndpointResponseDTO result =
                    endpointService.updateEndpoint(MERCHANT_ID, ENDPOINT_ID, update);

            assertThat(result.getUrl()).isEqualTo("https://new.com/hook");
            assertThat(result.isActive()).isFalse();
            assertThat(existing.getSecret()).isEqualTo("original-secret"); // not changed
        }

        @Test
        @DisplayName("non-owned endpoint → 404 WEBHOOK_ENDPOINT_NOT_FOUND")
        void updateEndpoint_notOwned_throws404() {
            when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> endpointService.updateEndpoint(MERCHANT_ID, ENDPOINT_ID,
                    validRequest))
                    .isInstanceOf(MembershipException.class)
                    .extracting(e -> ((MembershipException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── deactivateEndpoint ────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivateEndpoint → endpoint active flag set to false")
    void deactivateEndpoint_success() {
        MerchantWebhookEndpoint existing = endpoint(ENDPOINT_ID, "https://x.com/hook", true);
        when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                .thenReturn(Optional.of(existing));
        when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        endpointService.deactivateEndpoint(MERCHANT_ID, ENDPOINT_ID);

        assertThat(existing.isActive()).isFalse();
        verify(endpointRepository).save(existing);
    }

    @Test
    @DisplayName("deactivateEndpoint non-owned → 404")
    void deactivateEndpoint_notOwned_throws404() {
        when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> endpointService.deactivateEndpoint(MERCHANT_ID, ENDPOINT_ID))
                .isInstanceOf(MembershipException.class)
                .extracting(e -> ((MembershipException) e).getHttpStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MerchantWebhookEndpoint endpoint(Long id, String url, boolean active) {
        return MerchantWebhookEndpoint.builder()
                .id(id).merchantId(MERCHANT_ID).url(url).active(active)
                .secret("s3cr3t")
                .subscribedEventsJson("[\"invoice.paid\"]")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }
}
