package com.firstclub.risk.service;

import com.firstclub.risk.entity.IpBlocklist;
import com.firstclub.risk.entity.RiskEvent;
import com.firstclub.risk.entity.RiskEvent.RiskEventType;
import com.firstclub.risk.entity.RiskEvent.RiskSeverity;
import com.firstclub.risk.repository.IpBlocklistRepository;
import com.firstclub.risk.repository.RiskEventRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskServiceTest {

    @Mock RiskEventRepository     riskEventRepository;
    @Mock IpBlocklistRepository   ipBlocklistRepository;

    @InjectMocks RiskService riskService;

    private static final Long   USER_ID   = 42L;
    private static final String IP        = "192.168.1.1";
    private static final String DEVICE_ID = "dev-abc-123";

    // -------------------------------------------------------------------------
    // Happy path — no violations
    // -------------------------------------------------------------------------

    @Nested
    class CheckAndRecord {

        @Test
        void allowsCleanRequest_recordsPaymentAttempt() {
            when(ipBlocklistRepository.existsById(IP)).thenReturn(false);
            when(riskEventRepository.countPaymentAttemptsByUserSince(eq(USER_ID), any()))
                    .thenReturn(0L);

            assertThatCode(() -> riskService.checkAndRecord(USER_ID, IP, DEVICE_ID))
                    .doesNotThrowAnyException();

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(RiskEventType.PAYMENT_ATTEMPT);
            assertThat(captor.getValue().getSeverity()).isEqualTo(RiskSeverity.LOW);
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(captor.getValue().getIp()).isEqualTo(IP);
            assertThat(captor.getValue().getDeviceId()).isEqualTo(DEVICE_ID);
        }

        @Test
        void allowsRequestWithoutUserId_skipsVelocityCheck() {
            when(ipBlocklistRepository.existsById(IP)).thenReturn(false);

            // userId is null — countPaymentAttemptsByUserSince must NOT be called
            assertThatCode(() -> riskService.checkAndRecord(null, IP, DEVICE_ID))
                    .doesNotThrowAnyException();

            verify(riskEventRepository, never())
                    .countPaymentAttemptsByUserSince(any(), any());
        }

        // -------------------------------------------------------------------------
        // IP block
        // -------------------------------------------------------------------------

        @Test
        void blockedIp_throwsForbidden_recordsIpBlockedEvent() {
            when(ipBlocklistRepository.existsById(IP)).thenReturn(true);

            assertThatThrownBy(() -> riskService.checkAndRecord(USER_ID, IP, DEVICE_ID))
                    .isInstanceOf(RiskViolationException.class)
                    .satisfies(ex -> {
                        RiskViolationException rve = (RiskViolationException) ex;
                        assertThat(rve.getType()).isEqualTo(RiskEventType.IP_BLOCKED);
                    });

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(RiskEventType.IP_BLOCKED);
            assertThat(captor.getValue().getSeverity()).isEqualTo(RiskSeverity.HIGH);
        }

        @Test
        void blockedIp_doesNotProceedToVelocityCheck() {
            when(ipBlocklistRepository.existsById(IP)).thenReturn(true);

            assertThatThrownBy(() -> riskService.checkAndRecord(USER_ID, IP, DEVICE_ID))
                    .isInstanceOf(RiskViolationException.class);

            // velocity repo must never be consulted
            verify(riskEventRepository, never())
                    .countPaymentAttemptsByUserSince(any(), any());
        }

        // -------------------------------------------------------------------------
        // Velocity
        // -------------------------------------------------------------------------

        @Test
        void velocityExceeded_throwsTooManyRequests_recordsEvent() {
            when(ipBlocklistRepository.existsById(IP)).thenReturn(false);
            when(riskEventRepository.countPaymentAttemptsByUserSince(eq(USER_ID), any()))
                    .thenReturn((long) RiskService.VELOCITY_LIMIT);

            assertThatThrownBy(() -> riskService.checkAndRecord(USER_ID, IP, DEVICE_ID))
                    .isInstanceOf(RiskViolationException.class)
                    .satisfies(ex -> {
                        RiskViolationException rve = (RiskViolationException) ex;
                        assertThat(rve.getType()).isEqualTo(RiskEventType.VELOCITY_EXCEEDED);
                    });

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(RiskEventType.VELOCITY_EXCEEDED);
            assertThat(captor.getValue().getSeverity()).isEqualTo(RiskSeverity.MEDIUM);
        }

        @Test
        void velocityAtLimit_blocks_exactlyAtFiveAttempts() {
            when(ipBlocklistRepository.existsById(IP)).thenReturn(false);
            // exactly VELOCITY_LIMIT = 5 should trigger the block
            when(riskEventRepository.countPaymentAttemptsByUserSince(eq(USER_ID), any()))
                    .thenReturn(5L);

            assertThatThrownBy(() -> riskService.checkAndRecord(USER_ID, IP, DEVICE_ID))
                    .isInstanceOf(RiskViolationException.class);
        }

        @Test
        void velocityBelowLimit_allows_fourAttempts() {
            when(ipBlocklistRepository.existsById(IP)).thenReturn(false);
            when(riskEventRepository.countPaymentAttemptsByUserSince(eq(USER_ID), any()))
                    .thenReturn(4L);  // 4 < 5 → should pass

            assertThatCode(() -> riskService.checkAndRecord(USER_ID, IP, DEVICE_ID))
                    .doesNotThrowAnyException();
        }
    }

    // -------------------------------------------------------------------------
    // blockIp
    // -------------------------------------------------------------------------

    @Nested
    class BlockIp {

        @Test
        void blockIp_savesEntry() {
            riskService.blockIp(IP, "Fraud detected");

            ArgumentCaptor<IpBlocklist> captor = ArgumentCaptor.forClass(IpBlocklist.class);
            verify(ipBlocklistRepository).save(captor.capture());
            assertThat(captor.getValue().getIp()).isEqualTo(IP);
            assertThat(captor.getValue().getReason()).isEqualTo("Fraud detected");
        }
    }
}
