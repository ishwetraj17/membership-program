package com.firstclub.subscription.service;

import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.subscription.dto.SubscriptionScheduleCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionScheduleResponseDTO;
import com.firstclub.subscription.entity.SubscriptionSchedule;
import com.firstclub.subscription.entity.SubscriptionScheduledAction;
import com.firstclub.subscription.entity.SubscriptionScheduleStatus;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.exception.SubscriptionException;
import com.firstclub.subscription.mapper.SubscriptionScheduleMapper;
import com.firstclub.subscription.repository.SubscriptionScheduleRepository;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import com.firstclub.subscription.service.impl.SubscriptionScheduleServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionScheduleServiceImpl Unit Tests")
class SubscriptionScheduleServiceTest {

    @Mock private SubscriptionScheduleRepository scheduleRepository;
    @Mock private SubscriptionV2Repository subscriptionRepository;
    @Mock private SubscriptionScheduleMapper mapper;

    @InjectMocks
    private SubscriptionScheduleServiceImpl service;

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private MerchantAccount merchant;
    private SubscriptionV2 activeSub;
    private SubscriptionV2 cancelledSub;
    private SubscriptionSchedule scheduledEntry;
    private SubscriptionScheduleResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder().id(1L).merchantCode("M1")
                .legalName("Merchant One").status(MerchantStatus.ACTIVE).build();

        activeSub = SubscriptionV2.builder().id(50L).merchant(merchant)
                .status(SubscriptionStatusV2.ACTIVE).billingAnchorAt(LocalDateTime.now()).build();

        cancelledSub = SubscriptionV2.builder().id(60L).merchant(merchant)
                .status(SubscriptionStatusV2.CANCELLED).billingAnchorAt(LocalDateTime.now()).build();

        scheduledEntry = SubscriptionSchedule.builder().id(1L).subscription(activeSub)
                .scheduledAction(SubscriptionScheduledAction.CANCEL)
                .effectiveAt(LocalDateTime.now().plusDays(30))
                .status(SubscriptionScheduleStatus.SCHEDULED).build();

        responseDTO = SubscriptionScheduleResponseDTO.builder()
                .id(1L).subscriptionId(50L)
                .scheduledAction(SubscriptionScheduledAction.CANCEL)
                .status(SubscriptionScheduleStatus.SCHEDULED).build();
    }

    // ── CreateScheduleTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("createSchedule")
    class CreateScheduleTests {

        @Test
        @DisplayName("success — creates future CANCEL schedule")
        void createCancelSchedule() {
            SubscriptionScheduleCreateRequestDTO req = SubscriptionScheduleCreateRequestDTO.builder()
                    .scheduledAction(SubscriptionScheduledAction.CANCEL)
                    .effectiveAt(LocalDateTime.now().plusDays(30)).build();

            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSub));
            when(scheduleRepository.findBySubscriptionIdOrderByEffectiveAtAsc(50L))
                    .thenReturn(List.of());
            when(mapper.toEntity(req)).thenReturn(scheduledEntry);
            when(scheduleRepository.save(any())).thenReturn(scheduledEntry);
            when(mapper.toResponseDTO(scheduledEntry)).thenReturn(responseDTO);

            SubscriptionScheduleResponseDTO result = service.createSchedule(1L, 50L, req);
            assertThat(result.getScheduledAction()).isEqualTo(SubscriptionScheduledAction.CANCEL);
            assertThat(result.getStatus()).isEqualTo(SubscriptionScheduleStatus.SCHEDULED);
        }

        @Test
        @DisplayName("terminal subscription → 400")
        void terminalSubscriptionRejected() {
            SubscriptionScheduleCreateRequestDTO req = SubscriptionScheduleCreateRequestDTO.builder()
                    .scheduledAction(SubscriptionScheduledAction.CANCEL)
                    .effectiveAt(LocalDateTime.now().plusDays(10)).build();

            when(subscriptionRepository.findByMerchantIdAndId(1L, 60L))
                    .thenReturn(Optional.of(cancelledSub));

            assertThatThrownBy(() -> service.createSchedule(1L, 60L, req))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SUBSCRIPTION_TERMINAL");
        }

        @Test
        @DisplayName("effectiveAt in past → 400")
        void pastEffectiveAt() {
            SubscriptionScheduleCreateRequestDTO req = SubscriptionScheduleCreateRequestDTO.builder()
                    .scheduledAction(SubscriptionScheduledAction.PAUSE)
                    .effectiveAt(LocalDateTime.now().minusDays(1)).build();

            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSub));

            assertThatThrownBy(() -> service.createSchedule(1L, 50L, req))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SCHEDULE_EFFECTIVE_AT_IN_PAST");
        }

        @Test
        @DisplayName("duplicate SCHEDULED entry at same time → 409")
        void duplicateConflict() {
            LocalDateTime sameTime = LocalDateTime.now().plusDays(30);
            SubscriptionScheduleCreateRequestDTO req = SubscriptionScheduleCreateRequestDTO.builder()
                    .scheduledAction(SubscriptionScheduledAction.CANCEL)
                    .effectiveAt(sameTime).build();

            SubscriptionSchedule existing = SubscriptionSchedule.builder().id(2L)
                    .subscription(activeSub).scheduledAction(SubscriptionScheduledAction.PAUSE)
                    .effectiveAt(sameTime).status(SubscriptionScheduleStatus.SCHEDULED).build();

            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSub));
            when(scheduleRepository.findBySubscriptionIdOrderByEffectiveAtAsc(50L))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> service.createSchedule(1L, 50L, req))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("DUPLICATE_SCHEDULE_CONFLICT");
        }
    }

    // ── CancelScheduleTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelSchedule")
    class CancelScheduleTests {

        @Test
        @DisplayName("cancel SCHEDULED entry → CANCELLED")
        void cancelScheduled() {
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSub));
            when(scheduleRepository.findByIdAndSubscriptionId(1L, 50L))
                    .thenReturn(Optional.of(scheduledEntry));
            when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            SubscriptionScheduleResponseDTO cancelledDTO = SubscriptionScheduleResponseDTO.builder()
                    .id(1L).status(SubscriptionScheduleStatus.CANCELLED).build();
            when(mapper.toResponseDTO(any())).thenReturn(cancelledDTO);

            SubscriptionScheduleResponseDTO result = service.cancelSchedule(1L, 50L, 1L);
            assertThat(result.getStatus()).isEqualTo(SubscriptionScheduleStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancel already-EXECUTED entry → 400")
        void cancelExecuted() {
            SubscriptionSchedule executed = SubscriptionSchedule.builder().id(1L)
                    .subscription(activeSub).scheduledAction(SubscriptionScheduledAction.CANCEL)
                    .effectiveAt(LocalDateTime.now().minusHours(1))
                    .status(SubscriptionScheduleStatus.EXECUTED).build();

            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSub));
            when(scheduleRepository.findByIdAndSubscriptionId(1L, 50L))
                    .thenReturn(Optional.of(executed));

            assertThatThrownBy(() -> service.cancelSchedule(1L, 50L, 1L))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SCHEDULE_NOT_CANCELLABLE");
        }

        @Test
        @DisplayName("schedule not found → 404")
        void scheduleNotFound() {
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSub));
            when(scheduleRepository.findByIdAndSubscriptionId(99L, 50L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelSchedule(1L, 50L, 99L))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SCHEDULE_NOT_FOUND");
        }
    }

    // ── ListSchedulesTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("listSchedulesForSubscription")
    class ListSchedulesTests {

        @Test
        @DisplayName("returns schedules in ascending order")
        void listReturnsAll() {
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSub));
            when(scheduleRepository.findBySubscriptionIdOrderByEffectiveAtAsc(50L))
                    .thenReturn(List.of(scheduledEntry));
            when(mapper.toResponseDTO(scheduledEntry)).thenReturn(responseDTO);

            List<SubscriptionScheduleResponseDTO> results = service.listSchedulesForSubscription(1L, 50L);
            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("subscription not found (tenant isolation) → 404")
        void subscriptionNotFound() {
            when(subscriptionRepository.findByMerchantIdAndId(2L, 50L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listSchedulesForSubscription(2L, 50L))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SUBSCRIPTION_NOT_FOUND");
        }
    }
}
