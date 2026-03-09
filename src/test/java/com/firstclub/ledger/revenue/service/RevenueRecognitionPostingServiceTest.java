package com.firstclub.ledger.revenue.service;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.impl.RevenueRecognitionPostingServiceImpl;
import com.firstclub.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RevenueRecognitionPostingServiceImpl Unit Tests")
class RevenueRecognitionPostingServiceTest {

    @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
    @Mock private LedgerService ledgerService;
    @Mock private RevenueRecognitionPostingService selfMock;   // stands in for @Lazy self-injection
    @InjectMocks private RevenueRecognitionPostingServiceImpl service;

    @BeforeEach
    void injectSelf() {
        // The 'self' field is @Autowired @Lazy and therefore NOT injected by @InjectMocks.
        // Use ReflectionTestUtils to wire the mock so that transactional self-invocation
        // within postDueRecognitionsForDate is properly exercised in unit tests.
        ReflectionTestUtils.setField(service, "self", selfMock);
    }

    // =========================================================================
    // postDueRecognitionsForDate
    // =========================================================================

    @Nested
    @DisplayName("postDueRecognitionsForDate")
    class PostDueRecognitions {

        @Test
        @DisplayName("Posts all pending schedules and returns correct counts")
        void postsAllPendingSchedules() {
            LocalDate date = LocalDate.of(2024, 3, 15);
            RevenueRecognitionSchedule s1 = pendingSchedule(1L);
            RevenueRecognitionSchedule s2 = pendingSchedule(2L);

            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(date, RevenueRecognitionStatus.PENDING))
                    .thenReturn(List.of(s1, s2));
            doNothing().when(selfMock).postSingleRecognitionInRun(anyLong(), anyLong());

            RevenueRecognitionRunResponseDTO result = service.postDueRecognitionsForDate(date);

            assertThat(result.getScheduled()).isEqualTo(2);
            assertThat(result.getPosted()).isEqualTo(2);
            assertThat(result.getFailed()).isEqualTo(0);
            assertThat(result.getFailedScheduleIds()).isEmpty();
            assertThat(result.getDate()).isEqualTo(date.toString());

            verify(selfMock).postSingleRecognitionInRun(eq(1L), anyLong());
            verify(selfMock).postSingleRecognitionInRun(eq(2L), anyLong());
        }

        @Test
        @DisplayName("Counts failures correctly when postSingleRecognition throws")
        void countFailuresOnException() {
            LocalDate date = LocalDate.of(2024, 3, 15);
            RevenueRecognitionSchedule s1 = pendingSchedule(10L);
            RevenueRecognitionSchedule s2 = pendingSchedule(20L);

            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(date, RevenueRecognitionStatus.PENDING))
                    .thenReturn(List.of(s1, s2));
            doNothing().when(selfMock).postSingleRecognitionInRun(eq(10L), anyLong());
            doThrow(new RuntimeException("ledger account not found"))
                    .when(selfMock).postSingleRecognitionInRun(eq(20L), anyLong());

            RevenueRecognitionRunResponseDTO result = service.postDueRecognitionsForDate(date);

            assertThat(result.getScheduled()).isEqualTo(2);
            assertThat(result.getPosted()).isEqualTo(1);
            assertThat(result.getFailed()).isEqualTo(1);
            assertThat(result.getFailedScheduleIds()).containsExactly(20L);
        }

        @Test
        @DisplayName("Returns zero counts when no pending schedules exist for date")
        void noSchedulesReturnsZeroCounts() {
            LocalDate date = LocalDate.of(2024, 3, 1);
            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(date, RevenueRecognitionStatus.PENDING))
                    .thenReturn(List.of());

            RevenueRecognitionRunResponseDTO result = service.postDueRecognitionsForDate(date);

            assertThat(result.getScheduled()).isEqualTo(0);
            assertThat(result.getPosted()).isEqualTo(0);
            assertThat(result.getFailed()).isEqualTo(0);
        }

        @Test
        @DisplayName("Re-running same date is idempotent — repository returns no PENDING on second call")
        void reRunSameDateIsIdempotent() {
            LocalDate date = LocalDate.of(2024, 4, 1);
            // First run: 1 schedule to post
            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(date, RevenueRecognitionStatus.PENDING))
                    .thenReturn(List.of(pendingSchedule(5L)))
                    .thenReturn(List.of()); // second run: none left

            service.postDueRecognitionsForDate(date);
            RevenueRecognitionRunResponseDTO second = service.postDueRecognitionsForDate(date);

            assertThat(second.getScheduled()).isEqualTo(0);
            assertThat(second.getPosted()).isEqualTo(0);
        }
    }

    // =========================================================================
    // postSingleRecognition
    // =========================================================================

    @Nested
    @DisplayName("postSingleRecognition")
    class PostSingleRecognition {

        @Test
        @DisplayName("PENDING schedule — posts ledger entry and marks POSTED")
        void pendingScheduleIsPostedSuccessfully() {
            RevenueRecognitionSchedule schedule = pendingSchedule(100L);
            LedgerEntry entry = LedgerEntry.builder().id(999L).build();

            when(scheduleRepository.findByIdWithLock(100L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(eq(42L), eq(RevenueRecognitionStatus.POSTED)))
                    .thenReturn(BigDecimal.ZERO);
            when(scheduleRepository.sumTotalAmountByInvoiceId(42L)).thenReturn(new BigDecimal("100.00"));
            when(ledgerService.postEntry(
                    eq(LedgerEntryType.REVENUE_RECOGNIZED),
                    eq(LedgerReferenceType.REVENUE_RECOGNITION_SCHEDULE),
                    eq(100L),
                    eq("INR"),
                    anyList()))
                    .thenReturn(entry);
            when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.postSingleRecognition(100L);

            assertThat(schedule.getStatus()).isEqualTo(RevenueRecognitionStatus.POSTED);
            assertThat(schedule.getLedgerEntryId()).isEqualTo(999L);
            verify(scheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("PENDING schedule — posts DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS")
        void postsCorrectDoubleEntry() {
            RevenueRecognitionSchedule schedule = pendingSchedule(101L);
            LedgerEntry entry = LedgerEntry.builder().id(1001L).build();

            when(scheduleRepository.findByIdWithLock(101L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(eq(42L), eq(RevenueRecognitionStatus.POSTED)))
                    .thenReturn(BigDecimal.ZERO);
            when(scheduleRepository.sumTotalAmountByInvoiceId(42L)).thenReturn(new BigDecimal("100.00"));
            when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerLineRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
            when(ledgerService.postEntry(any(), any(), anyLong(), anyString(), linesCaptor.capture()))
                    .thenReturn(entry);

            service.postSingleRecognition(101L);

            List<LedgerLineRequest> lines = linesCaptor.getValue();
            assertThat(lines).hasSize(2);

            LedgerLineRequest debitLine = lines.stream()
                    .filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
            LedgerLineRequest creditLine = lines.stream()
                    .filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();

            assertThat(debitLine.getAccountName()).isEqualTo("SUBSCRIPTION_LIABILITY");
            assertThat(creditLine.getAccountName()).isEqualTo("REVENUE_SUBSCRIPTIONS");
            assertThat(debitLine.getAmount()).isEqualByComparingTo(new BigDecimal("33.3333"));
            assertThat(creditLine.getAmount()).isEqualByComparingTo(new BigDecimal("33.3333"));
        }

        @Test
        @DisplayName("Already POSTED — returns without calling ledgerService (idempotent)")
        void alreadyPostedIsIdempotent() {
            RevenueRecognitionSchedule schedule = RevenueRecognitionSchedule.builder()
                    .id(200L).status(RevenueRecognitionStatus.POSTED)
                    .amount(new BigDecimal("10.0000")).currency("INR")
                    .recognitionDate(LocalDate.now()).build();

            when(scheduleRepository.findByIdWithLock(200L)).thenReturn(Optional.of(schedule));

            service.postSingleRecognition(200L);

            verifyNoInteractions(ledgerService);
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Schedule not found — throws MembershipException")
        void scheduleNotFoundThrows() {
            when(scheduleRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.postSingleRecognition(999L))
                    .isInstanceOf(com.firstclub.membership.exception.MembershipException.class)
                    .hasMessageContaining("999");
        }

        @Test
        @DisplayName("Ledger posting failure — exception propagates (schedule remains PENDING for retry)")
        void ledgerFailurePropagates() {
            RevenueRecognitionSchedule schedule = pendingSchedule(300L);
            when(scheduleRepository.findByIdWithLock(300L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(eq(42L), eq(RevenueRecognitionStatus.POSTED)))
                    .thenReturn(BigDecimal.ZERO);
            when(scheduleRepository.sumTotalAmountByInvoiceId(42L)).thenReturn(new BigDecimal("100.00"));
            when(ledgerService.postEntry(any(), any(), anyLong(), anyString(), anyList()))
                    .thenThrow(new com.firstclub.membership.exception.MembershipException(
                            "Ledger account not found: SUBSCRIPTION_LIABILITY",
                            "LEDGER_ACCOUNT_NOT_FOUND"));

            assertThatThrownBy(() -> service.postSingleRecognition(300L))
                    .isInstanceOf(Exception.class);

            // Schedule status must NOT have been changed (still PENDING)
            assertThat(schedule.getStatus()).isEqualTo(RevenueRecognitionStatus.PENDING);
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private RevenueRecognitionSchedule pendingSchedule(Long id) {
        return RevenueRecognitionSchedule.builder()
                .id(id)
                .invoiceId(42L)
                .subscriptionId(7L)
                .merchantId(1L)
                .recognitionDate(LocalDate.of(2024, 3, 15))
                .amount(new BigDecimal("33.3333"))
                .currency("INR")
                .status(RevenueRecognitionStatus.PENDING)
                .build();
    }
}
