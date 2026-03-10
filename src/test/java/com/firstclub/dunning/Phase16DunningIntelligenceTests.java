package com.firstclub.dunning;

import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.classification.FailureCodeClassifier;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeResult;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.dunning.strategy.BackupPaymentMethodSelector;
import com.firstclub.dunning.strategy.DunningStrategyService;
import com.firstclub.dunning.strategy.DunningStrategyServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 16 — Dunning failure-code intelligence and backup payment strategy.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link FailureCodeClassifier} — code → category mapping</li>
 *   <li>{@link DunningStrategyServiceImpl} — category + policy → decision rules</li>
 *   <li>{@link BackupPaymentMethodSelector} — backup PM lookup</li>
 *   <li>{@link DunningDecisionAuditService} — decision fields stamped on attempt</li>
 *   <li>{@link ChargeResult} factory methods</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 16 — Dunning Failure-Code Intelligence")
class Phase16DunningIntelligenceTests {

    // =========================================================================
    // ChargeResult factory tests
    // =========================================================================

    @Nested
    @DisplayName("ChargeResult")
    class ChargeResultTests {

        @Test
        @DisplayName("success() returns SUCCESS outcome with null failureCode")
        void success_hasNullCode() {
            ChargeResult r = ChargeResult.success();
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.failureCode()).isNull();
        }

        @Test
        @DisplayName("failed(code) returns FAILED outcome with the given code")
        void failed_hasCode() {
            ChargeResult r = ChargeResult.failed("insufficient_funds");
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.failureCode()).isEqualTo("insufficient_funds");
        }

        @Test
        @DisplayName("failed(null) returns FAILED outcome with null code")
        void failed_nullCode() {
            ChargeResult r = ChargeResult.failed(null);
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.failureCode()).isNull();
        }
    }

    // =========================================================================
    // FailureCodeClassifier tests
    // =========================================================================

    @Nested
    @DisplayName("FailureCodeClassifier")
    class ClassifierTests {

        private final FailureCodeClassifier classifier = new FailureCodeClassifier();

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "insufficient_funds,           INSUFFICIENT_FUNDS",
            "card_declined,                CARD_DECLINED_GENERIC",
            "generic_decline,              CARD_DECLINED_GENERIC",
            "gateway_declined,             CARD_DECLINED_GENERIC",
            "simulated_decline,            CARD_DECLINED_GENERIC",
            "gateway_timeout,              GATEWAY_TIMEOUT",
            "processing_error,             GATEWAY_TIMEOUT",
            "expired_card,                 CARD_EXPIRED",
            "invalid_expiry_year,          CARD_EXPIRED",
            "card_not_supported,           CARD_NOT_SUPPORTED",
            "currency_not_supported,       CARD_NOT_SUPPORTED",
            "issuer_not_available,         ISSUER_NOT_AVAILABLE",
            "call_issuer,                  ISSUER_NOT_AVAILABLE",
            "stolen_card,                  CARD_STOLEN",
            "lost_card,                    CARD_LOST",
            "fraudulent,                   FRAUDULENT",
            "do_not_honor,                 DO_NOT_HONOR",
            "no_action_taken,              DO_NOT_HONOR",
            "invalid_account,              INVALID_ACCOUNT",
        })
        @DisplayName("known codes classify correctly")
        void knownCodes(String code, FailureCategory expected) {
            assertThat(classifier.classify(code)).isEqualTo(expected);
        }

        @Test
        @DisplayName("null input returns UNKNOWN")
        void nullCode_returnsUnknown() {
            assertThat(classifier.classify(null)).isEqualTo(FailureCategory.UNKNOWN);
        }

        @Test
        @DisplayName("blank input returns UNKNOWN")
        void blankCode_returnsUnknown() {
            assertThat(classifier.classify("   ")).isEqualTo(FailureCategory.UNKNOWN);
        }

        @Test
        @DisplayName("completely unknown code returns UNKNOWN")
        void unknownCode_returnsUnknown() {
            assertThat(classifier.classify("some_random_gateway_code_xyz")).isEqualTo(FailureCategory.UNKNOWN);
        }

        @Test
        @DisplayName("input is normalised to lower-case before lookup")
        void caseInsensitiveLookup() {
            assertThat(classifier.classify("EXPIRED_CARD")).isEqualTo(FailureCategory.CARD_EXPIRED);
            assertThat(classifier.classify("Stolen_Card")).isEqualTo(FailureCategory.CARD_STOLEN);
        }

        @Test
        @DisplayName("hyphens are normalised to underscores before lookup")
        void hyphenNormalisation() {
            assertThat(classifier.classify("do-not-honor")).isEqualTo(FailureCategory.DO_NOT_HONOR);
            assertThat(classifier.classify("expired-card")).isEqualTo(FailureCategory.CARD_EXPIRED);
        }
    }

    // =========================================================================
    // DunningStrategyServiceImpl tests
    // =========================================================================

    @Nested
    @DisplayName("DunningStrategyService")
    class StrategyTests {

        @Mock private DunningAttemptRepository    dunningAttemptRepository;
        @Mock private BackupPaymentMethodSelector backupSelector;

        @InjectMocks
        private DunningStrategyServiceImpl strategyService;

        private static final Long SUB_ID    = 10L;
        private static final Long POLICY_ID = 3L;

        private DunningAttempt attempt(boolean usedBackup) {
            return DunningAttempt.builder()
                    .id(500L)
                    .subscriptionId(SUB_ID)
                    .invoiceId(50L)
                    .attemptNumber(1)
                    .scheduledAt(LocalDateTime.now())
                    .status(DunningStatus.FAILED)
                    .dunningPolicyId(POLICY_ID)
                    .usedBackupMethod(usedBackup)
                    .build();
        }

        private DunningPolicy policy(boolean fallback) {
            return DunningPolicy.builder()
                    .id(POLICY_ID).merchantId(1L).policyCode("DEFAULT")
                    .retryOffsetsJson("[60,360]").maxAttempts(2).graceDays(7)
                    .fallbackToBackupPaymentMethod(fallback)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED)
                    .build();
        }

        // ── Non-retryable rules ───────────────────────────────────────────────

        @ParameterizedTest(name = "{0} → STOP")
        @CsvSource({"CARD_STOLEN", "CARD_LOST", "FRAUDULENT", "DO_NOT_HONOR", "INVALID_ACCOUNT"})
        @DisplayName("non-retryable categories always return STOP")
        void nonRetryable_returnsStop(FailureCategory category) {
            DunningDecision decision = strategyService.decide(attempt(false), category, policy(false));
            assertThat(decision).isEqualTo(DunningDecision.STOP);
            verifyNoInteractions(dunningAttemptRepository, backupSelector);
        }

        // ── Backup rules ──────────────────────────────────────────────────────

        @ParameterizedTest(name = "{0} + backup eligible → RETRY_WITH_BACKUP")
        @CsvSource({"CARD_EXPIRED", "CARD_NOT_SUPPORTED", "ISSUER_NOT_AVAILABLE"})
        @DisplayName("backup-needing category with backup eligible → RETRY_WITH_BACKUP")
        void backupCategory_backupEligible_returnsRetryWithBackup(FailureCategory category) {
            when(backupSelector.findBackup(SUB_ID)).thenReturn(Optional.of(200L));
            DunningDecision decision = strategyService.decide(attempt(false), category, policy(true));
            assertThat(decision).isEqualTo(DunningDecision.RETRY_WITH_BACKUP);
        }

        @ParameterizedTest(name = "{0} + no backup → STOP")
        @CsvSource({"CARD_EXPIRED", "CARD_NOT_SUPPORTED", "ISSUER_NOT_AVAILABLE"})
        @DisplayName("backup-needing category with no backup configured → STOP")
        void backupCategory_noBackup_returnsStop(FailureCategory category) {
            when(backupSelector.findBackup(SUB_ID)).thenReturn(Optional.empty());
            DunningDecision decision = strategyService.decide(attempt(false), category, policy(true));
            assertThat(decision).isEqualTo(DunningDecision.STOP);
        }

        @Test
        @DisplayName("backup-needing category when policy disallows fallback → STOP")
        void backupCategory_policyDisallowsFallback_returnsStop() {
            DunningDecision decision = strategyService.decide(
                    attempt(false), FailureCategory.CARD_EXPIRED, policy(/*fallback=*/false));
            assertThat(decision).isEqualTo(DunningDecision.STOP);
            verifyNoInteractions(backupSelector); // no point checking selector
        }

        @Test
        @DisplayName("backup-needing category but already on backup attempt → STOP")
        void backupCategory_alreadyOnBackup_returnsStop() {
            DunningDecision decision = strategyService.decide(
                    attempt(/*usedBackup=*/true), FailureCategory.CARD_EXPIRED, policy(true));
            assertThat(decision).isEqualTo(DunningDecision.STOP);
            verifyNoInteractions(backupSelector);
        }

        // ── Retryable / exhausted rules ───────────────────────────────────────

        @ParameterizedTest(name = "{0} + remaining attempts → RETRY")
        @CsvSource({"INSUFFICIENT_FUNDS", "CARD_DECLINED_GENERIC", "GATEWAY_TIMEOUT", "UNKNOWN"})
        @DisplayName("retryable category with remaining attempts → RETRY")
        void retryable_withRemainingAttempts_returnsRetry(FailureCategory category) {
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUB_ID, DunningStatus.SCHEDULED)).thenReturn(2L);
            DunningDecision decision = strategyService.decide(attempt(false), category, policy(false));
            assertThat(decision).isEqualTo(DunningDecision.RETRY);
        }

        @ParameterizedTest(name = "{0} + no remaining attempts → EXHAUSTED")
        @CsvSource({"INSUFFICIENT_FUNDS", "CARD_DECLINED_GENERIC", "GATEWAY_TIMEOUT", "UNKNOWN"})
        @DisplayName("retryable category with no remaining attempts → EXHAUSTED")
        void retryable_noRemainingAttempts_returnsExhausted(FailureCategory category) {
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUB_ID, DunningStatus.SCHEDULED)).thenReturn(0L);
            DunningDecision decision = strategyService.decide(attempt(false), category, policy(false));
            assertThat(decision).isEqualTo(DunningDecision.EXHAUSTED);
        }
    }

    // =========================================================================
    // BackupPaymentMethodSelector tests
    // =========================================================================

    @Nested
    @DisplayName("BackupPaymentMethodSelector")
    class BackupSelectorTests {

        @Mock
        private com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository preferenceRepository;

        @InjectMocks
        private BackupPaymentMethodSelector selector;

        @Test
        @DisplayName("returns backup PM id when preference has backup configured")
        void findBackup_withBackup_returnsId() {
            var pref = com.firstclub.dunning.entity.SubscriptionPaymentPreference.builder()
                    .subscriptionId(10L)
                    .primaryPaymentMethodId(100L)
                    .backupPaymentMethodId(200L)
                    .build();
            when(preferenceRepository.findBySubscriptionId(10L)).thenReturn(Optional.of(pref));

            Optional<Long> result = selector.findBackup(10L);

            assertThat(result).contains(200L);
        }

        @Test
        @DisplayName("returns empty when preference has no backup PM")
        void findBackup_noBackup_returnsEmpty() {
            var pref = com.firstclub.dunning.entity.SubscriptionPaymentPreference.builder()
                    .subscriptionId(10L)
                    .primaryPaymentMethodId(100L)
                    .backupPaymentMethodId(null)
                    .build();
            when(preferenceRepository.findBySubscriptionId(10L)).thenReturn(Optional.of(pref));

            assertThat(selector.findBackup(10L)).isEmpty();
        }

        @Test
        @DisplayName("returns empty when no preference row exists")
        void findBackup_noPreference_returnsEmpty() {
            when(preferenceRepository.findBySubscriptionId(10L)).thenReturn(Optional.empty());

            assertThat(selector.findBackup(10L)).isEmpty();
        }
    }

    // =========================================================================
    // DunningDecisionAuditService tests
    // =========================================================================

    @Nested
    @DisplayName("DunningDecisionAuditService")
    class AuditServiceTests {

        @Mock
        private DunningAttemptRepository dunningAttemptRepository;

        @InjectMocks
        private DunningDecisionAuditService auditService;

        @Test
        @DisplayName("record() stamps all decision fields on the attempt")
        void record_stampsAllFields() {
            DunningAttempt attempt = DunningAttempt.builder()
                    .id(500L).subscriptionId(10L).invoiceId(50L)
                    .attemptNumber(1).scheduledAt(LocalDateTime.now())
                    .status(DunningStatus.FAILED).dunningPolicyId(3L)
                    .build();
            when(dunningAttemptRepository.findById(500L)).thenReturn(Optional.of(attempt));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            auditService.record(500L, DunningDecision.STOP, "Non-retryable: stolen_card",
                    FailureCategory.CARD_STOLEN, true);

            verify(dunningAttemptRepository).save(attempt);
            assertThat(attempt.getFailureCategory()).isEqualTo("CARD_STOLEN");
            assertThat(attempt.getDecisionTaken()).isEqualTo("STOP");
            assertThat(attempt.getDecisionReason()).contains("stolen_card");
            assertThat(attempt.isStoppedEarly()).isTrue();
        }

        @Test
        @DisplayName("record() stamps RETRY decision with stoppedEarly=false")
        void record_retryDecision_stoppedEarlyFalse() {
            DunningAttempt attempt = DunningAttempt.builder()
                    .id(501L).subscriptionId(10L).invoiceId(50L)
                    .attemptNumber(1).scheduledAt(LocalDateTime.now())
                    .status(DunningStatus.FAILED).dunningPolicyId(3L)
                    .build();
            when(dunningAttemptRepository.findById(501L)).thenReturn(Optional.of(attempt));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            auditService.record(501L, DunningDecision.RETRY, "Retryable failure",
                    FailureCategory.INSUFFICIENT_FUNDS, false);

            assertThat(attempt.getDecisionTaken()).isEqualTo("RETRY");
            assertThat(attempt.getFailureCategory()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(attempt.isStoppedEarly()).isFalse();
        }

        @Test
        @DisplayName("record() is a no-op when attempt does not exist")
        void record_attemptNotFound_noOp() {
            when(dunningAttemptRepository.findById(999L)).thenReturn(Optional.empty());

            // Must not throw
            auditService.record(999L, DunningDecision.STOP, "reason",
                    FailureCategory.FRAUDULENT, true);

            verify(dunningAttemptRepository, never()).save(any());
        }

        @Test
        @DisplayName("record() handles null failure category without NPE")
        void record_nullCategory_handledGracefully() {
            DunningAttempt attempt = DunningAttempt.builder()
                    .id(502L).subscriptionId(10L).invoiceId(50L)
                    .attemptNumber(1).scheduledAt(LocalDateTime.now())
                    .status(DunningStatus.FAILED).dunningPolicyId(3L)
                    .build();
            when(dunningAttemptRepository.findById(502L)).thenReturn(Optional.of(attempt));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            auditService.record(502L, DunningDecision.EXHAUSTED, "exhausted", null, false);

            assertThat(attempt.getFailureCategory()).isNull();
            assertThat(attempt.getDecisionTaken()).isEqualTo("EXHAUSTED");
        }
    }

    // =========================================================================
    // DunningDecision enum coverage
    // =========================================================================

    @Nested
    @DisplayName("DunningDecision enum")
    class DunningDecisionEnumTests {

        @Test
        @DisplayName("all expected values are present")
        void allValuesPresent() {
            assertThat(DunningDecision.values()).containsExactlyInAnyOrder(
                    DunningDecision.RETRY,
                    DunningDecision.RETRY_WITH_BACKUP,
                    DunningDecision.STOP,
                    DunningDecision.EXHAUSTED
            );
        }
    }
}
