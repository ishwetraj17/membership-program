package com.firstclub.risk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.risk.dto.RiskDecisionResponseDTO;
import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskDecision;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.RiskDecisionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RiskDecisionService Unit Tests")
class RiskDecisionServiceTest {

    @Mock private RiskRuleService        ruleService;
    @Mock private RiskDecisionRepository decisionRepository;
    @Mock private ManualReviewService    reviewService;
    @Spy  private ObjectMapper           objectMapper = new ObjectMapper();

    @InjectMocks
    private RiskDecisionService service;

    private static final Long MERCHANT_ID  = 1L;
    private static final Long INTENT_ID    = 10L;
    private static final Long CUSTOMER_ID  = 20L;

    private final RiskContext ctx = new RiskContext(MERCHANT_ID, INTENT_ID, CUSTOMER_ID, null, null);

    private RiskDecision savedDecision(RiskAction decision, int score) {
        return RiskDecision.builder()
                .id(99L).merchantId(MERCHANT_ID).paymentIntentId(INTENT_ID)
                .customerId(CUSTOMER_ID).score(score).decision(decision)
                .matchedRulesJson("[]").build();
    }

    private RiskRule rule(RiskAction action) {
        return RiskRule.builder().id(1L).ruleCode("RULE").ruleType("T")
                .configJson("{\"score\":40}").action(action).active(true).priority(1).build();
    }

    @Nested
    @DisplayName("evaluateForPaymentIntent")
    class Evaluate {

        @Test
        @DisplayName("no matched rules → ALLOW decision, no review case created")
        void noMatchedRules_allowDecision() {
            when(ruleService.evaluateRules(ctx)).thenReturn(new RiskEvaluationResult(List.of(), 0));
            when(decisionRepository.save(any())).thenReturn(savedDecision(RiskAction.ALLOW, 0));

            RiskDecisionResponseDTO result = service.evaluateForPaymentIntent(ctx);

            assertThat(result.decision()).isEqualTo(RiskAction.ALLOW);
            assertThat(result.score()).isZero();
            verify(reviewService, never()).createCase(any(), any(), any());
        }

        @Test
        @DisplayName("BLOCK rule matches → BLOCK decision, no review case")
        void blockRule_blockDecision_noReviewCase() {
            when(ruleService.evaluateRules(ctx))
                    .thenReturn(new RiskEvaluationResult(List.of(rule(RiskAction.BLOCK)), 40));
            when(decisionRepository.save(any())).thenReturn(savedDecision(RiskAction.BLOCK, 40));

            RiskDecisionResponseDTO result = service.evaluateForPaymentIntent(ctx);

            assertThat(result.decision()).isEqualTo(RiskAction.BLOCK);
            verify(reviewService, never()).createCase(any(), any(), any());
        }

        @Test
        @DisplayName("REVIEW rule matches → REVIEW decision AND review case created")
        void reviewRule_createsReviewCase() {
            when(ruleService.evaluateRules(ctx))
                    .thenReturn(new RiskEvaluationResult(List.of(rule(RiskAction.REVIEW)), 40));
            when(decisionRepository.save(any())).thenReturn(savedDecision(RiskAction.REVIEW, 40));

            RiskDecisionResponseDTO result = service.evaluateForPaymentIntent(ctx);

            assertThat(result.decision()).isEqualTo(RiskAction.REVIEW);
            verify(reviewService).createCase(MERCHANT_ID, INTENT_ID, CUSTOMER_ID);
        }

        @Test
        @DisplayName("CHALLENGE rule matches → CHALLENGE decision, no review case")
        void challengeRule_challengeDecision_noReviewCase() {
            when(ruleService.evaluateRules(ctx))
                    .thenReturn(new RiskEvaluationResult(List.of(rule(RiskAction.CHALLENGE)), 40));
            when(decisionRepository.save(any())).thenReturn(savedDecision(RiskAction.CHALLENGE, 40));

            RiskDecisionResponseDTO result = service.evaluateForPaymentIntent(ctx);

            assertThat(result.decision()).isEqualTo(RiskAction.CHALLENGE);
            verify(reviewService, never()).createCase(any(), any(), any());
        }

        @Test
        @DisplayName("BLOCK + REVIEW rules both match → BLOCK takes precedence")
        void blockPrecedenceOverReview() {
            List<RiskRule> rules = List.of(rule(RiskAction.BLOCK), rule(RiskAction.REVIEW));
            when(ruleService.evaluateRules(ctx))
                    .thenReturn(new RiskEvaluationResult(rules, 80));
            when(decisionRepository.save(any())).thenReturn(savedDecision(RiskAction.BLOCK, 80));

            RiskDecisionResponseDTO result = service.evaluateForPaymentIntent(ctx);

            assertThat(result.decision()).isEqualTo(RiskAction.BLOCK);
            verify(reviewService, never()).createCase(any(), any(), any());
        }

        @Test
        @DisplayName("multiple matching rules → score is their sum")
        void multipleMatchedRules_scoresAggregated() {
            RiskRule r1 = rule(RiskAction.ALLOW);
            RiskRule r2 = rule(RiskAction.CHALLENGE);
            when(ruleService.evaluateRules(ctx))
                    .thenReturn(new RiskEvaluationResult(List.of(r1, r2), 80));
            when(decisionRepository.save(any())).thenReturn(savedDecision(RiskAction.CHALLENGE, 80));

            RiskDecisionResponseDTO result = service.evaluateForPaymentIntent(ctx);

            assertThat(result.score()).isEqualTo(80);
        }
    }

    @Nested
    @DisplayName("deriveDecision")
    class DeriveDecision {

        @Test @DisplayName("empty list → ALLOW")
        void empty_allow() {
            assertThat(service.deriveDecision(List.of())).isEqualTo(RiskAction.ALLOW);
        }

        @Test @DisplayName("only ALLOW rules → ALLOW")
        void onlyAllow_allow() {
            assertThat(service.deriveDecision(List.of(rule(RiskAction.ALLOW)))).isEqualTo(RiskAction.ALLOW);
        }

        @Test @DisplayName("REVIEW without BLOCK → REVIEW")
        void reviewWithoutBlock_review() {
            assertThat(service.deriveDecision(List.of(rule(RiskAction.REVIEW)))).isEqualTo(RiskAction.REVIEW);
        }

        @Test @DisplayName("BLOCK present → always BLOCK regardless of others")
        void blockPresent_block() {
            assertThat(service.deriveDecision(
                    List.of(rule(RiskAction.REVIEW), rule(RiskAction.BLOCK), rule(RiskAction.CHALLENGE))))
                    .isEqualTo(RiskAction.BLOCK);
        }
    }

    @Nested
    @DisplayName("listDecisions")
    class ListDecisions {

        @Test
        @DisplayName("with merchantId — delegates to filtered repository method")
        void withMerchantId_filteredQuery() {
            when(decisionRepository.findByMerchantIdOrderByCreatedAtDesc(eq(MERCHANT_ID), any()))
                    .thenReturn(Page.empty());

            Page<RiskDecisionResponseDTO> page = service.listDecisions(MERCHANT_ID, Pageable.unpaged());

            assertThat(page).isEmpty();
            verify(decisionRepository).findByMerchantIdOrderByCreatedAtDesc(eq(MERCHANT_ID), any());
            verify(decisionRepository, never()).findAllByOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("without merchantId — delegates to unfiltered repository method")
        void withoutMerchantId_unfilteredQuery() {
            RiskDecision d = savedDecision(RiskAction.ALLOW, 0);
            when(decisionRepository.findAllByOrderByCreatedAtDesc(any()))
                    .thenReturn(new PageImpl<>(List.of(d)));

            Page<RiskDecisionResponseDTO> page = service.listDecisions(null, Pageable.unpaged());

            assertThat(page.getTotalElements()).isEqualTo(1);
            verify(decisionRepository, never()).findByMerchantIdOrderByCreatedAtDesc(any(), any());
        }
    }
}
