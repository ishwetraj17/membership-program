package com.firstclub.risk;

import com.firstclub.risk.dto.ManualReviewCaseResponseDTO;
import com.firstclub.risk.entity.ManualReviewCase;
import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskDecision;
import com.firstclub.risk.entity.ReviewCaseStatus;
import com.firstclub.risk.repository.ManualReviewCaseRepository;
import com.firstclub.risk.repository.RiskDecisionRepository;
import com.firstclub.risk.repository.RiskEventRepository;
import com.firstclub.risk.review.ManualReviewEscalationService;
import com.firstclub.risk.scoring.RiskDecisionExplanation;
import com.firstclub.risk.scoring.RiskDecisionExplainer;
import com.firstclub.risk.scoring.RiskPostureSummary;
import com.firstclub.risk.scoring.RiskPostureService;
import com.firstclub.risk.scoring.RiskScoreDecayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 18 unit tests — Risk score decay, posture, escalation, and explainability.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 18 — Risk Decay, Posture, Escalation & Explainability")
class Phase18RiskDecayTests {

    // =========================================================================
    // RiskScoreDecayService
    // =========================================================================

    @Nested
    @DisplayName("RiskScoreDecayService")
    class DecayTests {

        @Mock RiskEventRepository riskEventRepository;
        @InjectMocks RiskScoreDecayService decayService;

        @Test
        @DisplayName("age 0 h returns full base score")
        void decay_zeroAge_returnsBase() {
            // event created just now
            LocalDateTime now = LocalDateTime.now().minusSeconds(1);
            int result = decayService.decayedScore(100, now);
            // at t≈0 the score should be very close to 100
            assertThat(result).isBetween(99, 100);
        }

        @Test
        @DisplayName("age == halfLife returns approximately half the base score")
        void decay_atHalfLife_returnsHalf() {
            // Create event 72 hours ago (default half-life)
            LocalDateTime created = LocalDateTime.now().minusHours(72);
            int result = decayService.decayedScore(100, created);
            // should be ~50 (within rounding tolerance of 1)
            assertThat(result).isBetween(49, 51);
        }

        @Test
        @DisplayName("age == 2x halfLife returns approximately quarter of base score")
        void decay_atDoubleHalfLife_returnsQuarter() {
            LocalDateTime created = LocalDateTime.now().minusHours(144);
            int result = decayService.decayedScore(100, created);
            assertThat(result).isBetween(23, 27);
        }

        @Test
        @DisplayName("negative halfLife throws IllegalArgumentException")
        void decay_negativeHalfLife_throws() {
            assertThatThrownBy(() -> decayService.decayedScore(100, LocalDateTime.now(), -1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero halfLife throws IllegalArgumentException")
        void decay_zeroHalfLife_throws() {
            assertThatThrownBy(() -> decayService.decayedScore(100, LocalDateTime.now(), 0.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("decayAll skips events with null baseScore")
        void decayAll_skipsNullBaseScore() {
            com.firstclub.risk.entity.RiskEvent event = new com.firstclub.risk.entity.RiskEvent();
            // baseScore is null by default
            List<com.firstclub.risk.entity.RiskEvent> result = decayService.decayAll(List.of(event));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDecayedScore()).isNull();
            verify(riskEventRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("decayAll updates decayedScore for events with baseScore")
        void decayAll_updatesDecayedScore() {
            com.firstclub.risk.entity.RiskEvent event = new com.firstclub.risk.entity.RiskEvent();
            event.setBaseScore(80);
            event.setCreatedAt(LocalDateTime.now().minusMinutes(1));
            when(riskEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            decayService.decayAll(List.of(event));

            assertThat(event.getDecayedScore()).isNotNull().isPositive();
            verify(riskEventRepository).saveAll(anyList());
        }
    }

    // =========================================================================
    // ManualReviewService — SLA stamp on createCase
    // =========================================================================

    @Nested
    @DisplayName("ManualReviewService — SLA stamp")
    class ManualReviewSlaTests {

        @Mock ManualReviewCaseRepository caseRepository;
        @InjectMocks com.firstclub.risk.service.ManualReviewService reviewService;

        @Test
        @DisplayName("createCase sets slaDueAt ~24 hours from now")
        void createCase_setsSLADeadline() {
            LocalDateTime before = LocalDateTime.now();
            when(caseRepository.save(any())).thenAnswer(inv -> {
                ManualReviewCase c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            ManualReviewCase created = reviewService.createCase(1L, 10L, 20L);

            assertThat(created.getSlaDueAt()).isNotNull();
            assertThat(created.getSlaDueAt()).isAfterOrEqualTo(before.plusHours(23));
            assertThat(created.getSlaDueAt()).isBeforeOrEqualTo(before.plusHours(25));
        }
    }

    // =========================================================================
    // ManualReviewEscalationService
    // =========================================================================

    @Nested
    @DisplayName("ManualReviewEscalationService")
    class EscalationTests {

        @Mock ManualReviewCaseRepository caseRepository;
        @InjectMocks ManualReviewEscalationService escalationService;

        private ManualReviewCase openCase(Long id) {
            ManualReviewCase c = new ManualReviewCase();
            c.setId(id);
            c.setStatus(ReviewCaseStatus.OPEN);
            c.setSlaDueAt(LocalDateTime.now().minusHours(1)); // already overdue
            return c;
        }

        @Test
        @DisplayName("escalateOverdueCases marks OPEN cases as ESCALATED with escalatedAt stamp")
        void escalateOverdue_setsStatusAndTimestamp() {
            ManualReviewCase c1 = openCase(1L);
            ManualReviewCase c2 = openCase(2L);
            when(caseRepository.findOverdueCases(any())).thenReturn(List.of(c1, c2));
            when(caseRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            int count = escalationService.escalateOverdueCases();

            assertThat(count).isEqualTo(2);
            assertThat(c1.getStatus()).isEqualTo(ReviewCaseStatus.ESCALATED);
            assertThat(c1.getEscalatedAt()).isNotNull();
            assertThat(c2.getStatus()).isEqualTo(ReviewCaseStatus.ESCALATED);
            assertThat(c2.getEscalatedAt()).isNotNull();
        }

        @Test
        @DisplayName("escalateOverdueCases returns 0 when no overdue cases")
        void escalateOverdue_noOverdueCases_returnsZero() {
            when(caseRepository.findOverdueCases(any())).thenReturn(List.of());
            int count = escalationService.escalateOverdueCases();
            assertThat(count).isZero();
            verify(caseRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("escalateCase manually escalates an OPEN case")
        void escalateCase_open_succeeds() {
            ManualReviewCase c = openCase(42L);
            when(caseRepository.findById(42L)).thenReturn(Optional.of(c));
            when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ManualReviewCaseResponseDTO result = escalationService.escalateCase(42L, "needs senior review");

            assertThat(c.getStatus()).isEqualTo(ReviewCaseStatus.ESCALATED);
            assertThat(c.getEscalatedAt()).isNotNull();
            assertThat(c.getDecisionReason()).isEqualTo("needs senior review");
        }

        @Test
        @DisplayName("escalateCase throws 404 for non-existent case")
        void escalateCase_notFound_throws404() {
            when(caseRepository.findById(999L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> escalationService.escalateCase(999L, "reason"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("escalateCase throws 409 for already-terminal case")
        void escalateCase_terminal_throws409() {
            ManualReviewCase c = openCase(5L);
            c.setStatus(ReviewCaseStatus.APPROVED);
            when(caseRepository.findById(5L)).thenReturn(Optional.of(c));
            assertThatThrownBy(() -> escalationService.escalateCase(5L, "late"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("terminal");
        }

        @Test
        @DisplayName("escalateCase throws 409 for already-escalated case")
        void escalateCase_alreadyEscalated_throws409() {
            ManualReviewCase c = openCase(7L);
            c.setStatus(ReviewCaseStatus.ESCALATED);
            when(caseRepository.findById(7L)).thenReturn(Optional.of(c));
            assertThatThrownBy(() -> escalationService.escalateCase(7L, "dup"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("already escalated");
        }
    }

    // =========================================================================
    // RiskDecisionExplainer
    // =========================================================================

    @Nested
    @DisplayName("RiskDecisionExplainer")
    class ExplainerTests {

        @Mock RiskDecisionRepository riskDecisionRepository;
        @Mock RiskEventRepository riskEventRepository;
        @InjectMocks RiskScoreDecayService decayService;

        // Note: @InjectMocks cannot be used for both decayService and explainer
        // in the same test class — we construct explainer manually.

        @Test
        @DisplayName("explain returns decision with triggererd rule IDs parsed from JSON")
        void explain_returnsExplanationWithRuleIds() {
            RiskScoreDecayService decay = new RiskScoreDecayService(riskEventRepository);
            RiskDecisionExplainer explainer = new RiskDecisionExplainer(riskDecisionRepository, decay);

            RiskDecision decision = RiskDecision.builder()
                    .id(1L).merchantId(1L).paymentIntentId(100L).customerId(5L)
                    .score(75).decision(RiskAction.BLOCK)
                    .matchedRulesJson("[{\"id\":3,\"ruleCode\":\"IP_VELOCITY\",\"action\":\"BLOCK\"},{\"id\":7,\"ruleCode\":\"DEVICE_REUSE\",\"action\":\"CHALLENGE\"}]")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();
            when(riskDecisionRepository.findTopByPaymentIntentIdOrderByCreatedAtDesc(100L))
                    .thenReturn(Optional.of(decision));

            RiskDecisionExplanation explanation = explainer.explain(100L);

            assertThat(explanation.paymentIntentId()).isEqualTo(100L);
            assertThat(explanation.decision()).isEqualTo(RiskAction.BLOCK);
            assertThat(explanation.score()).isEqualTo(75);
            assertThat(explanation.triggeredRuleIds()).containsExactlyInAnyOrder(3L, 7L);
            assertThat(explanation.explanation()).isNotBlank();
            assertThat(explanation.explanation()).contains("BLOCK");
        }

        @Test
        @DisplayName("explain returns decayed score lower than base for old decision")
        void explain_decayedScoreLowerThanBase_forOldDecision() {
            RiskScoreDecayService decay = new RiskScoreDecayService(riskEventRepository);
            RiskDecisionExplainer explainer = new RiskDecisionExplainer(riskDecisionRepository, decay);

            RiskDecision decision = RiskDecision.builder()
                    .id(2L).merchantId(1L).paymentIntentId(200L).customerId(5L)
                    .score(100).decision(RiskAction.REVIEW)
                    .matchedRulesJson("[]")
                    .createdAt(LocalDateTime.now().minusHours(72)) // 1 half-life ago
                    .build();
            when(riskDecisionRepository.findTopByPaymentIntentIdOrderByCreatedAtDesc(200L))
                    .thenReturn(Optional.of(decision));

            RiskDecisionExplanation explanation = explainer.explain(200L);

            assertThat(explanation.decayedScore()).isLessThan(explanation.score());
            assertThat(explanation.decayedScore()).isBetween(45, 55); // ~50% of 100
        }

        @Test
        @DisplayName("explain throws 404 when no decision exists")
        void explain_noDecision_throws404() {
            RiskScoreDecayService decay = new RiskScoreDecayService(riskEventRepository);
            RiskDecisionExplainer explainer = new RiskDecisionExplainer(riskDecisionRepository, decay);

            when(riskDecisionRepository.findTopByPaymentIntentIdOrderByCreatedAtDesc(999L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> explainer.explain(999L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No risk decision found");
        }
    }

    // =========================================================================
    // RiskPostureService
    // =========================================================================

    @Nested
    @DisplayName("RiskPostureService")
    class PostureTests {

        @Mock RiskDecisionRepository riskDecisionRepository;
        @InjectMocks RiskPostureService postureService;

        @Test
        @DisplayName("getPosture returns correct counts and dominant action")
        void getPosture_returnsCorrectSummary() {
            List<RiskDecision> decisions = List.of(
                    decision(RiskAction.BLOCK, 90),
                    decision(RiskAction.BLOCK, 80),
                    decision(RiskAction.REVIEW, 60),
                    decision(RiskAction.ALLOW, 10)
            );
            when(riskDecisionRepository.findByMerchantIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(decisions));

            RiskPostureSummary summary = postureService.getPosture(1L);

            assertThat(summary.merchantId()).isEqualTo(1L);
            assertThat(summary.recentDecisionCount()).isEqualTo(4);
            assertThat(summary.blockCount()).isEqualTo(2);
            assertThat(summary.reviewCount()).isEqualTo(1);
            assertThat(summary.allowCount()).isEqualTo(1);
            assertThat(summary.dominantAction()).isEqualTo(RiskAction.BLOCK);
            assertThat(summary.avgScore()).isEqualTo(60); // (90+80+60+10)/4
        }

        @Test
        @DisplayName("getPosture throws 404 when merchant has no decisions")
        void getPosture_noDecisions_throws404() {
            when(riskDecisionRepository.findByMerchantIdOrderByCreatedAtDesc(eq(99L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            assertThatThrownBy(() -> postureService.getPosture(99L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No risk decisions found");
        }

        private RiskDecision decision(RiskAction action, int score) {
            return RiskDecision.builder()
                    .id(System.nanoTime()).merchantId(1L).paymentIntentId(1L)
                    .customerId(1L).score(score).decision(action)
                    .matchedRulesJson("[]").createdAt(LocalDateTime.now())
                    .build();
        }
    }
}
