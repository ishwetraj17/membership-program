package com.firstclub.notifications.webhooks.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookDeliveryResponseDTO;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDelivery;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookEndpoint;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookEndpointRepository;
import com.firstclub.notifications.webhooks.service.impl.MerchantWebhookDeliveryServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantWebhookDeliveryService Unit Tests")
class MerchantWebhookDeliveryServiceTest {

    @Mock
    private MerchantWebhookEndpointRepository endpointRepository;

    @Mock
    private MerchantWebhookDeliveryRepository deliveryRepository;

    @Mock
    private WebhookDispatcher webhookDispatcher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MerchantWebhookDeliveryServiceImpl deliveryService;

    private static final Long MERCHANT_ID  = 1L;
    private static final Long ENDPOINT_ID  = 10L;
    private static final Long DELIVERY_ID  = 100L;
    private static final String SECRET     = "test-secret-value";
    private static final String PAYLOAD    = "{\"invoiceId\":42}";
    private static final String EVENT_TYPE = "invoice.paid";

    private MerchantWebhookEndpoint activeEndpoint;

    @BeforeEach
    void setUp() {
        activeEndpoint = MerchantWebhookEndpoint.builder()
                .id(ENDPOINT_ID).merchantId(MERCHANT_ID)
                .url("https://example.com/hook").secret(SECRET).active(true)
                .subscribedEventsJson("[\"invoice.paid\",\"payment.failed\"]")
                .build();
    }

    // ── signPayload ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("signPayload")
    class SignPayload {

        @Test
        @DisplayName("signature starts with sha256= and is 71 chars long")
        void signPayload_formatCorrect() {
            String sig = deliveryService.signPayload(PAYLOAD, SECRET);
            // "sha256=" (7) + 64 hex chars = 71
            assertThat(sig).startsWith("sha256=").hasSize(71);
        }

        @Test
        @DisplayName("same input always produces the same signature (deterministic)")
        void signPayload_deterministic() {
            String sig1 = deliveryService.signPayload(PAYLOAD, SECRET);
            String sig2 = deliveryService.signPayload(PAYLOAD, SECRET);
            assertThat(sig1).isEqualTo(sig2);
        }

        @Test
        @DisplayName("different secret → different signature")
        void signPayload_differentSecretDifferentSignature() {
            String sig1 = deliveryService.signPayload(PAYLOAD, "secret-a");
            String sig2 = deliveryService.signPayload(PAYLOAD, "secret-b");
            assertThat(sig1).isNotEqualTo(sig2);
        }

        @Test
        @DisplayName("different payload → different signature")
        void signPayload_differentPayloadDifferentSignature() {
            String sig1 = deliveryService.signPayload("{\"a\":1}", SECRET);
            String sig2 = deliveryService.signPayload("{\"b\":2}", SECRET);
            assertThat(sig1).isNotEqualTo(sig2);
        }
    }

    // ── enqueueDeliveryForMerchantEvent ───────────────────────────────────────

    @Nested
    @DisplayName("enqueueDeliveryForMerchantEvent")
    class EnqueueDelivery {

        @Test
        @DisplayName("subscribed endpoint → delivery record created with PENDING status")
        void enqueue_subscribedEndpoint_createsDelivery() {
            when(endpointRepository.findActiveByMerchantId(MERCHANT_ID))
                    .thenReturn(List.of(activeEndpoint));
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.enqueueDeliveryForMerchantEvent(MERCHANT_ID, EVENT_TYPE, PAYLOAD);

            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository).save(cap.capture());

            MerchantWebhookDelivery saved = cap.getValue();
            assertThat(saved.getEndpointId()).isEqualTo(ENDPOINT_ID);
            assertThat(saved.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(saved.getPayload()).isEqualTo(PAYLOAD);
            assertThat(saved.getStatus()).isEqualTo(PENDING);
            assertThat(saved.getSignature()).startsWith("sha256=");
        }

        @Test
        @DisplayName("endpoint not subscribed to event type → no delivery created")
        void enqueue_notSubscribed_noDeliveryCreated() {
            MerchantWebhookEndpoint otherEndpoint = MerchantWebhookEndpoint.builder()
                    .id(ENDPOINT_ID).merchantId(MERCHANT_ID)
                    .url("https://example.com/hook").secret(SECRET).active(true)
                    .subscribedEventsJson("[\"subscription.activated\"]")
                    .build();
            when(endpointRepository.findActiveByMerchantId(MERCHANT_ID))
                    .thenReturn(List.of(otherEndpoint));

            deliveryService.enqueueDeliveryForMerchantEvent(MERCHANT_ID, "invoice.paid", PAYLOAD);

            verifyNoInteractions(deliveryRepository);
        }

        @Test
        @DisplayName("wildcard subscription → delivery created for any event type")
        void enqueue_wildcardSubscription_alwaysDelivers() {
            MerchantWebhookEndpoint wildcard = MerchantWebhookEndpoint.builder()
                    .id(ENDPOINT_ID).merchantId(MERCHANT_ID)
                    .url("https://example.com/hook").secret(SECRET).active(true)
                    .subscribedEventsJson("[\"*\"]")
                    .build();
            when(endpointRepository.findActiveByMerchantId(MERCHANT_ID))
                    .thenReturn(List.of(wildcard));
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.enqueueDeliveryForMerchantEvent(MERCHANT_ID, "dispute.opened", PAYLOAD);

            verify(deliveryRepository).save(any(MerchantWebhookDelivery.class));
        }

        @Test
        @DisplayName("no active endpoints → no deliveries created")
        void enqueue_noActiveEndpoints_noOp() {
            when(endpointRepository.findActiveByMerchantId(MERCHANT_ID))
                    .thenReturn(Collections.emptyList());

            deliveryService.enqueueDeliveryForMerchantEvent(MERCHANT_ID, EVENT_TYPE, PAYLOAD);

            verifyNoInteractions(deliveryRepository);
        }
    }

    // ── retryDueDeliveries ────────────────────────────────────────────────────

    @Nested
    @DisplayName("retryDueDeliveries")
    class RetryDueDeliveries {

        private MerchantWebhookDelivery pendingDelivery;

        @BeforeEach
        void setUp() {
            pendingDelivery = MerchantWebhookDelivery.builder()
                    .id(DELIVERY_ID).endpointId(ENDPOINT_ID)
                    .eventType(EVENT_TYPE).payload(PAYLOAD)
                    .signature("sha256=abc").status(PENDING).attemptCount(0)
                    .nextAttemptAt(LocalDateTime.now().minusMinutes(1))
                    .build();
        }

        @Test
        @DisplayName("no due deliveries → no-op")
        void retryDueDeliveries_noDue_noOp() {
            when(deliveryRepository.findDueForProcessingWithSkipLocked(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Collections.emptyList());

            deliveryService.retryDueDeliveries();

            verifyNoInteractions(endpointRepository, webhookDispatcher);
        }

        @Test
        @DisplayName("2xx response → delivery marked DELIVERED")
        void retryDueDeliveries_success_marksDelivered() {
            stubDueDeliveries(pendingDelivery);
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(activeEndpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(200);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository, atLeast(1)).save(cap.capture());

            MerchantWebhookDelivery saved = cap.getAllValues().stream()
                    .filter(d -> d.getId().equals(DELIVERY_ID)).findFirst().orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(DELIVERED);
            assertThat(saved.getAttemptCount()).isEqualTo(1);
            assertThat(saved.getNextAttemptAt()).isNull();
        }

        @Test
        @DisplayName("non-2xx response → delivery marked FAILED with next retry scheduled")
        void retryDueDeliveries_failure_schedulesRetry() {
            stubDueDeliveries(pendingDelivery);
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(activeEndpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(500);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository, atLeast(1)).save(cap.capture());

            MerchantWebhookDelivery saved = cap.getAllValues().stream()
                    .filter(d -> d.getId().equals(DELIVERY_ID)).findFirst().orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(FAILED);
            assertThat(saved.getNextAttemptAt()).isNotNull().isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("connection failure (dispatcher returns -1) → FAILED with retry")
        void retryDueDeliveries_connectionFailure_schedulesRetry() {
            stubDueDeliveries(pendingDelivery);
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(activeEndpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(-1);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository, atLeast(1)).save(cap.capture());

            boolean hasFailed = cap.getAllValues().stream()
                    .anyMatch(d -> d.getId().equals(DELIVERY_ID) && d.getStatus() == FAILED);
            assertThat(hasFailed).isTrue();
        }

        @Test
        @DisplayName("MAX_ATTEMPTS reached → delivery marked GAVE_UP")
        void retryDueDeliveries_maxAttempts_marksGaveUp() {
            pendingDelivery.setAttemptCount(MerchantWebhookDeliveryServiceImpl.MAX_ATTEMPTS - 1);
            pendingDelivery.setStatus(FAILED);
            stubDueDeliveries(pendingDelivery);
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(activeEndpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(503);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deliveryRepository.countByEndpointIdAndStatus(eq(ENDPOINT_ID), any()))
                    .thenReturn(0L);

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository, atLeast(1)).save(cap.capture());

            boolean hasGaveUp = cap.getAllValues().stream()
                    .anyMatch(d -> d.getId().equals(DELIVERY_ID) && d.getStatus() == GAVE_UP);
            assertThat(hasGaveUp).isTrue();
        }

        @Test
        @DisplayName("disabled endpoint → delivery immediately GAVE_UP without HTTP call")
        void retryDueDeliveries_disabledEndpoint_gaveUpWithoutDispatch() {
            MerchantWebhookEndpoint disabled = MerchantWebhookEndpoint.builder()
                    .id(ENDPOINT_ID).merchantId(MERCHANT_ID)
                    .url("https://example.com/hook").secret(SECRET).active(false)
                    .subscribedEventsJson("[\"invoice.paid\"]").build();
            stubDueDeliveries(pendingDelivery);
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(disabled));
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.retryDueDeliveries();

            verifyNoInteractions(webhookDispatcher);
            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(GAVE_UP);
        }
    }

    // ── auto-disable ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GAVE_UP count >= threshold → endpoint auto-disabled")
    void autoDisable_thresholdReached_endpointDeactivated() {
        // Build a delivery that already has MAX_ATTEMPTS
        MerchantWebhookDelivery delivery = MerchantWebhookDelivery.builder()
                .id(DELIVERY_ID).endpointId(ENDPOINT_ID)
                .eventType(EVENT_TYPE).payload(PAYLOAD).signature("sha256=x")
                .status(FAILED)
                .attemptCount(MerchantWebhookDeliveryServiceImpl.MAX_ATTEMPTS - 1)
                .nextAttemptAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(deliveryRepository.findDueForProcessingWithSkipLocked(any(LocalDateTime.class), anyInt())).thenReturn(List.of(delivery));
        when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(activeEndpoint));
        when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(500);
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryRepository.countByEndpointIdAndStatus(eq(ENDPOINT_ID), eq(GAVE_UP)))
                .thenReturn((long) MerchantWebhookDeliveryServiceImpl.DISABLE_AFTER_GAVE_UP);
        when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deliveryService.retryDueDeliveries();

        ArgumentCaptor<MerchantWebhookEndpoint> epCap =
                ArgumentCaptor.forClass(MerchantWebhookEndpoint.class);
        verify(endpointRepository, atLeast(1)).save(epCap.capture());
        // The secondary guard (maybeDisableUnhealthyEndpoint) must have deactivated the endpoint
        assertThat(epCap.getAllValues()).anyMatch(ep -> !ep.isActive());
    }

    // ── getDelivery tenant isolation ──────────────────────────────────────────

    @Test
    @DisplayName("getDelivery with wrong merchant → 404 WEBHOOK_DELIVERY_NOT_FOUND")
    void getDelivery_wrongMerchant_throws404() {
        MerchantWebhookDelivery delivery = MerchantWebhookDelivery.builder()
                .id(DELIVERY_ID).endpointId(ENDPOINT_ID)
                .eventType(EVENT_TYPE).payload(PAYLOAD).signature("sha256=x")
                .status(DELIVERED).attemptCount(1)
                .build();
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        // endpoint belongs to a different merchant
        when(endpointRepository.findByMerchantIdAndId(99L, ENDPOINT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> deliveryService.getDelivery(99L, DELIVERY_ID))
                .isInstanceOf(MembershipException.class)
                .extracting(e -> ((MembershipException) e).getHttpStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("getDelivery with correct merchant → returns DTO")
    void getDelivery_correctMerchant_returnsDto() {
        MerchantWebhookDelivery delivery = MerchantWebhookDelivery.builder()
                .id(DELIVERY_ID).endpointId(ENDPOINT_ID)
                .eventType(EVENT_TYPE).payload(PAYLOAD).signature("sha256=abc")
                .status(DELIVERED).attemptCount(1).lastResponseCode(200)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                .thenReturn(Optional.of(activeEndpoint));

        MerchantWebhookDeliveryResponseDTO dto = deliveryService.getDelivery(MERCHANT_ID, DELIVERY_ID);

        assertThat(dto.getId()).isEqualTo(DELIVERY_ID);
        assertThat(dto.getStatus()).isEqualTo(DELIVERED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubDueDeliveries(MerchantWebhookDelivery... deliveries) {
        when(deliveryRepository.findDueForProcessingWithSkipLocked(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(deliveries));
    }
}
