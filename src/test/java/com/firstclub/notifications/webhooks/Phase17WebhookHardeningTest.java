package com.firstclub.notifications.webhooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookDeliveryResponseDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookPingResponseDTO;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDelivery;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookEndpoint;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookEndpointRepository;
import com.firstclub.notifications.webhooks.service.WebhookDispatcher;
import com.firstclub.notifications.webhooks.service.impl.MerchantWebhookDeliveryServiceImpl;
import com.firstclub.platform.repair.RepairAction.RepairContext;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.platform.repair.actions.WebhookDeliveryRetryAction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus.*;
import static com.firstclub.notifications.webhooks.service.impl.MerchantWebhookDeliveryServiceImpl.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 17: Webhook Delivery Hardening — unit-test coverage for:
 * <ul>
 *   <li>Retry collision prevention (WebhookDeliveryRetryAction guards)</li>
 *   <li>Consecutive-failure auto-disable (primary mechanism)</li>
 *   <li>Ping endpoint dispatch</li>
 *   <li>Delivery search with filter delegation</li>
 *   <li>Fingerprint-based enqueue deduplication</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Phase 17: Webhook Delivery Hardening")
class Phase17WebhookHardeningTest {

    // ── shared mocks wired into MerchantWebhookDeliveryServiceImpl ────────────

    @Mock  MerchantWebhookEndpointRepository endpointRepository;
    @Mock  MerchantWebhookDeliveryRepository deliveryRepository;
    @Mock  WebhookDispatcher                  webhookDispatcher;
    @Spy   ObjectMapper                        objectMapper = new ObjectMapper();

    @InjectMocks
    MerchantWebhookDeliveryServiceImpl deliveryService;

    // ── test constants ────────────────────────────────────────────────────────

    private static final Long   MERCHANT_ID  = 1L;
    private static final Long   ENDPOINT_ID  = 10L;
    private static final Long   DELIVERY_ID  = 100L;
    private static final String SECRET       = "test-secret";
    private static final String PAYLOAD      = "{\"invoiceId\":42}";
    private static final String EVENT_TYPE   = "invoice.paid";

    // ── helpers ───────────────────────────────────────────────────────────────

    private MerchantWebhookEndpoint activeEndpoint() {
        return MerchantWebhookEndpoint.builder()
                .id(ENDPOINT_ID).merchantId(MERCHANT_ID)
                .url("https://example.com/hook").secret(SECRET)
                .active(true).consecutiveFailures(0)
                .subscribedEventsJson("[\"invoice.paid\"]")
                .build();
    }

    private MerchantWebhookDelivery pendingDelivery(MerchantWebhookDeliveryStatus status) {
        return MerchantWebhookDelivery.builder()
                .id(DELIVERY_ID).endpointId(ENDPOINT_ID)
                .eventType(EVENT_TYPE).payload(PAYLOAD)
                .signature("sha256=abc").status(status).attemptCount(0)
                .nextAttemptAt(LocalDateTime.now().minusMinutes(1))
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Retry Collision Prevention (WebhookDeliveryRetryAction)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RetryAction collision prevention")
    class CollisionPrevention {

        /** Use the shared deliveryRepository mock and objectMapper spy. */
        private WebhookDeliveryRetryAction retryAction;

        private RepairContext ctx(String targetId) {
            return new RepairContext(targetId, Map.of(), false, null, null);
        }

        @BeforeEach
        void setUp() {
            // Construct manually so it uses the shared mock
            retryAction = new WebhookDeliveryRetryAction(deliveryRepository, objectMapper);
        }

        @Test
        @DisplayName("DELIVERED delivery → refused with success=false")
        void execute_deliveredDelivery_refusedWithSuccessFalse() {
            MerchantWebhookDelivery delivery = pendingDelivery(DELIVERED);
            when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

            RepairActionResult result = retryAction.execute(ctx(DELIVERY_ID.toString()));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getDetails()).contains("already DELIVERED");
            // delivery must not be modified
            verify(deliveryRepository, never()).save(any());
        }

        @Test
        @DisplayName("In-flight delivery (processingStartedAt set) → refused with success=false")
        void execute_inFlightDelivery_refusedWithSuccessFalse() {
            MerchantWebhookDelivery delivery = pendingDelivery(FAILED);
            delivery.setProcessingStartedAt(LocalDateTime.now().minusSeconds(10));
            delivery.setProcessingOwner("node-1:12345");
            when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

            RepairActionResult result = retryAction.execute(ctx(DELIVERY_ID.toString()));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getDetails()).contains("in-flight");
            assertThat(result.getDetails()).contains("node-1:12345");
            verify(deliveryRepository, never()).save(any());
        }

        @Test
        @DisplayName("GAVE_UP delivery → resets to PENDING and returns success=true")
        void execute_gaveUpDelivery_resetsToPendingSuccessTrue() {
            MerchantWebhookDelivery delivery = pendingDelivery(GAVE_UP);
            when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RepairActionResult result = retryAction.execute(ctx(DELIVERY_ID.toString()));

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(PENDING);
        }

        @Test
        @DisplayName("FAILED delivery → resets to PENDING and returns success=true")
        void execute_failedDelivery_resetsToPendingSuccessTrue() {
            MerchantWebhookDelivery delivery = pendingDelivery(FAILED);
            when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RepairActionResult result = retryAction.execute(ctx(DELIVERY_ID.toString()));

            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. Consecutive-Failure Auto-Disable (primary mechanism)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Consecutive-failure auto-disable")
    class AutoDisable {

        @Test
        @DisplayName("Non-2xx dispatch → consecutiveFailures incremented and endpoint saved")
        void dispatch_nonTwoXx_incrementsConsecutiveFailures() {
            MerchantWebhookDelivery delivery = pendingDelivery(PENDING);
            MerchantWebhookEndpoint endpoint = activeEndpoint();

            when(deliveryRepository.findDueForProcessingWithSkipLocked(any(), anyInt()))
                    .thenReturn(List.of(delivery));
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(500);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deliveryRepository.countByEndpointIdAndStatus(any(), any())).thenReturn(0L);

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookEndpoint> epCap =
                    ArgumentCaptor.forClass(MerchantWebhookEndpoint.class);
            verify(endpointRepository).save(epCap.capture());
            assertThat(epCap.getValue().getConsecutiveFailures()).isEqualTo(1);
        }

        @Test
        @DisplayName("consecutiveFailures reaches threshold → endpoint auto-disabled with autoDisabledAt set")
        void dispatch_thresholdReached_endpointAutoDisabled() {
            MerchantWebhookDelivery delivery = pendingDelivery(PENDING);
            MerchantWebhookEndpoint endpoint = activeEndpoint();
            // Prime counter to one below threshold
            endpoint.setConsecutiveFailures(CONSECUTIVE_FAILURE_THRESHOLD - 1);

            when(deliveryRepository.findDueForProcessingWithSkipLocked(any(), anyInt()))
                    .thenReturn(List.of(delivery));
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(503);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deliveryRepository.countByEndpointIdAndStatus(any(), any())).thenReturn(0L);

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookEndpoint> epCap =
                    ArgumentCaptor.forClass(MerchantWebhookEndpoint.class);
            verify(endpointRepository).save(epCap.capture());
            MerchantWebhookEndpoint saved = epCap.getValue();
            assertThat(saved.isActive()).isFalse();
            assertThat(saved.getAutoDisabledAt()).isNotNull();
            assertThat(saved.getConsecutiveFailures()).isEqualTo(CONSECUTIVE_FAILURE_THRESHOLD);
        }

        @Test
        @DisplayName("Auto-disabled endpoint (autoDisabledAt set + active=true) skipped during enqueue")
        void enqueue_autoDisabledEndpoint_skipped() {
            MerchantWebhookEndpoint autoDisabled = activeEndpoint();
            autoDisabled.setAutoDisabledAt(LocalDateTime.now().minusHours(1));

            when(endpointRepository.findActiveByMerchantId(MERCHANT_ID))
                    .thenReturn(List.of(autoDisabled));

            deliveryService.enqueueDeliveryForMerchantEvent(MERCHANT_ID, EVENT_TYPE, PAYLOAD);

            verifyNoInteractions(deliveryRepository);
        }

        @Test
        @DisplayName("2xx dispatch → consecutiveFailures reset to 0")
        void dispatch_successAfterFailures_resetsConsecutiveFailures() {
            MerchantWebhookDelivery delivery = pendingDelivery(PENDING);
            MerchantWebhookEndpoint endpoint = activeEndpoint();
            endpoint.setConsecutiveFailures(3);

            when(deliveryRepository.findDueForProcessingWithSkipLocked(any(), anyInt()))
                    .thenReturn(List.of(delivery));
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(200);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookEndpoint> epCap =
                    ArgumentCaptor.forClass(MerchantWebhookEndpoint.class);
            verify(endpointRepository).save(epCap.capture());
            assertThat(epCap.getValue().getConsecutiveFailures()).isEqualTo(0);
        }

        @Test
        @DisplayName("Already auto-disabled previously → threshold not re-applied (autoDisabledAt preserved)")
        void dispatch_alreadyAutoDisabled_noDoubleDisable() {
            MerchantWebhookDelivery delivery = pendingDelivery(PENDING);
            MerchantWebhookEndpoint endpoint = activeEndpoint();
            LocalDateTime firstDisable = LocalDateTime.now().minusHours(2);
            endpoint.setAutoDisabledAt(firstDisable);
            endpoint.setConsecutiveFailures(CONSECUTIVE_FAILURE_THRESHOLD + 3);

            when(deliveryRepository.findDueForProcessingWithSkipLocked(any(), anyInt()))
                    .thenReturn(List.of(delivery));
            when(endpointRepository.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(500);
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deliveryRepository.countByEndpointIdAndStatus(any(), any())).thenReturn(0L);

            deliveryService.retryDueDeliveries();

            ArgumentCaptor<MerchantWebhookEndpoint> epCap =
                    ArgumentCaptor.forClass(MerchantWebhookEndpoint.class);
            verify(endpointRepository).save(epCap.capture());
            // autoDisabledAt must not be overwritten with a newer timestamp
            assertThat(epCap.getValue().getAutoDisabledAt()).isEqualTo(firstDisable);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. Ping Endpoint
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Ping endpoint")
    class Ping {

        @Test
        @DisplayName("Successful ping → DELIVERED delivery, success message returned")
        void ping_success_returnsDeliveredResult() {
            MerchantWebhookEndpoint endpoint = activeEndpoint();
            MerchantWebhookDelivery saved    = pendingDelivery(PENDING);
            saved.setId(DELIVERY_ID);

            when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                    .thenReturn(Optional.of(endpoint));
            when(deliveryRepository.save(any())).thenAnswer(inv -> {
                MerchantWebhookDelivery d = inv.getArgument(0);
                d.setId(DELIVERY_ID);
                return d;
            });
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), eq(PING_EVENT_TYPE), any()))
                    .thenReturn(200);

            MerchantWebhookPingResponseDTO result = deliveryService.pingEndpoint(MERCHANT_ID, ENDPOINT_ID);

            assertThat(result.getEndpointId()).isEqualTo(ENDPOINT_ID);
            assertThat(result.getStatus()).isEqualTo(DELIVERED.name());
            assertThat(result.getMessage()).contains("successfully");
        }

        @Test
        @DisplayName("Dispatcher called with PING_EVENT_TYPE")
        void ping_dispatcherCalledWithPingEventType() {
            MerchantWebhookEndpoint endpoint = activeEndpoint();

            when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                    .thenReturn(Optional.of(endpoint));
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(200);

            deliveryService.pingEndpoint(MERCHANT_ID, ENDPOINT_ID);

            verify(webhookDispatcher).dispatch(
                    anyString(), anyString(), anyString(), eq(PING_EVENT_TYPE), any());
        }

        @Test
        @DisplayName("Inactive endpoint → 422 UNPROCESSABLE_ENTITY")
        void ping_inactiveEndpoint_throws422() {
            MerchantWebhookEndpoint inactive = activeEndpoint();
            inactive.setActive(false);

            when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                    .thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> deliveryService.pingEndpoint(MERCHANT_ID, ENDPOINT_ID))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(e -> assertThat(((MembershipException) e).getHttpStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("Endpoint not found for merchant → 404")
        void ping_endpointNotFound_throws404() {
            when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> deliveryService.pingEndpoint(MERCHANT_ID, ENDPOINT_ID))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(e -> assertThat(((MembershipException) e).getHttpStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("Failed ping (non-2xx) → failure message returned, no consecutive failure increment")
        void ping_dispatchFails_failureMessageReturnedNoConsecutiveIncrement() {
            MerchantWebhookEndpoint endpoint = activeEndpoint();

            when(endpointRepository.findByMerchantIdAndId(MERCHANT_ID, ENDPOINT_ID))
                    .thenReturn(Optional.of(endpoint));
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), eq(PING_EVENT_TYPE), any()))
                    .thenReturn(500);
            when(deliveryRepository.countByEndpointIdAndStatus(any(), any())).thenReturn(0L);

            MerchantWebhookPingResponseDTO result = deliveryService.pingEndpoint(MERCHANT_ID, ENDPOINT_ID);

            assertThat(result.getStatus()).isNotEqualTo(DELIVERED.name());
            // Consecutive failures should NOT be incremented for ping
            verify(endpointRepository, never()).save(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. Delivery Search
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Delivery search")
    class DeliverySearch {

        @Test
        @DisplayName("Valid search → delegates to repository with correct parameters")
        void search_validParams_delegatesToRepository() {
            LocalDateTime from = LocalDateTime.now().minusDays(7);
            LocalDateTime to   = LocalDateTime.now();

            when(deliveryRepository.searchDeliveries(eq(MERCHANT_ID), eq(EVENT_TYPE),
                    eq(DELIVERED), eq(200), eq(from), eq(to), any(Pageable.class)))
                    .thenReturn(List.of());

            List<MerchantWebhookDeliveryResponseDTO> results =
                    deliveryService.searchDeliveries(MERCHANT_ID, EVENT_TYPE, "DELIVERED", 200, from, to, 50);

            assertThat(results).isEmpty();
            verify(deliveryRepository).searchDeliveries(eq(MERCHANT_ID), eq(EVENT_TYPE),
                    eq(DELIVERED), eq(200), eq(from), eq(to), any(Pageable.class));
        }

        @Test
        @DisplayName("Null filters accepted → null status passed to repository")
        void search_nullFilters_passedThrough() {
            when(deliveryRepository.searchDeliveries(eq(MERCHANT_ID), isNull(), isNull(),
                    isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(List.of());

            deliveryService.searchDeliveries(MERCHANT_ID, null, null, null, null, null, 50);

            verify(deliveryRepository).searchDeliveries(eq(MERCHANT_ID), isNull(), isNull(),
                    isNull(), isNull(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("Invalid status string → throws 400 BAD_REQUEST")
        void search_invalidStatus_throws400() {
            assertThatThrownBy(() ->
                    deliveryService.searchDeliveries(MERCHANT_ID, null, "BOGUS_STATUS", null, null, null, 50))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(e -> assertThat(((MembershipException) e).getHttpStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("Limit > 500 → capped to 500")
        void search_largeLimit_cappedAt500() {
            when(deliveryRepository.searchDeliveries(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            deliveryService.searchDeliveries(MERCHANT_ID, null, null, null, null, null, 9999);

            ArgumentCaptor<Pageable> pageableCap = ArgumentCaptor.forClass(Pageable.class);
            verify(deliveryRepository).searchDeliveries(any(), any(), any(), any(), any(), any(),
                    pageableCap.capture());
            assertThat(pageableCap.getValue().getPageSize()).isEqualTo(500);
        }

        @Test
        @DisplayName("Limit of 0 → defaults to 50")
        void search_zeroLimit_defaultsToFifty() {
            when(deliveryRepository.searchDeliveries(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            deliveryService.searchDeliveries(MERCHANT_ID, null, null, null, null, null, 0);

            ArgumentCaptor<Pageable> pageableCap = ArgumentCaptor.forClass(Pageable.class);
            verify(deliveryRepository).searchDeliveries(any(), any(), any(), any(), any(), any(),
                    pageableCap.capture());
            assertThat(pageableCap.getValue().getPageSize()).isEqualTo(50);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. Fingerprint Deduplication
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Fingerprint deduplication")
    class FingerprintDedup {

        @Test
        @DisplayName("DELIVERED fingerprint already exists → enqueue skipped (no save)")
        void enqueue_deliveredFingerprintExists_skipped() {
            MerchantWebhookEndpoint endpoint = activeEndpoint();
            String fingerprint = computeDeliveryFingerprint(ENDPOINT_ID, EVENT_TYPE, PAYLOAD);
            MerchantWebhookDelivery existing = pendingDelivery(DELIVERED);

            when(endpointRepository.findActiveByMerchantId(MERCHANT_ID))
                    .thenReturn(List.of(endpoint));
            when(deliveryRepository.findTopByDeliveryFingerprintAndStatus(fingerprint, DELIVERED))
                    .thenReturn(Optional.of(existing));

            deliveryService.enqueueDeliveryForMerchantEvent(MERCHANT_ID, EVENT_TYPE, PAYLOAD);

            verify(deliveryRepository, never()).save(any());
        }

        @Test
        @DisplayName("No existing DELIVERED fingerprint → delivery created and fingerprint stored")
        void enqueue_noExistingDeliveredFingerprint_createsDeliveryWithFingerprint() {
            MerchantWebhookEndpoint endpoint = activeEndpoint();
            String expectedFingerprint = computeDeliveryFingerprint(ENDPOINT_ID, EVENT_TYPE, PAYLOAD);

            when(endpointRepository.findActiveByMerchantId(MERCHANT_ID))
                    .thenReturn(List.of(endpoint));
            when(deliveryRepository.findTopByDeliveryFingerprintAndStatus(any(), any()))
                    .thenReturn(Optional.empty());
            when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            deliveryService.enqueueDeliveryForMerchantEvent(MERCHANT_ID, EVENT_TYPE, PAYLOAD);

            ArgumentCaptor<MerchantWebhookDelivery> cap =
                    ArgumentCaptor.forClass(MerchantWebhookDelivery.class);
            verify(deliveryRepository).save(cap.capture());
            assertThat(cap.getValue().getDeliveryFingerprint()).isEqualTo(expectedFingerprint);
        }

        @Test
        @DisplayName("computeDeliveryFingerprint is deterministic for same inputs")
        void computeFingerprint_deterministic() {
            String fp1 = computeDeliveryFingerprint(ENDPOINT_ID, EVENT_TYPE, PAYLOAD);
            String fp2 = computeDeliveryFingerprint(ENDPOINT_ID, EVENT_TYPE, PAYLOAD);
            assertThat(fp1).isEqualTo(fp2);
        }

        @Test
        @DisplayName("computeDeliveryFingerprint differs for different payloads")
        void computeFingerprint_differentInputs_differentFingerprints() {
            String fp1 = computeDeliveryFingerprint(ENDPOINT_ID, EVENT_TYPE, "{\"a\":1}");
            String fp2 = computeDeliveryFingerprint(ENDPOINT_ID, EVENT_TYPE, "{\"b\":2}");
            assertThat(fp1).isNotEqualTo(fp2);
        }
    }
}
