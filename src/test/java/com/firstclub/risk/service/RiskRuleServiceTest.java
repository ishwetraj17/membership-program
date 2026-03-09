package com.firstclub.risk.service;

import com.firstclub.risk.dto.RiskRuleCreateRequestDTO;
import com.firstclub.risk.dto.RiskRuleResponseDTO;
import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.RiskRuleRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RiskRuleService Unit Tests")
class RiskRuleServiceTest {

    @Mock
    private RiskRuleRepository ruleRepository;

    private RiskRuleService service;

    private static final Long MERCHANT_ID = 1L;

    // Stub evaluators for deterministic tests
    private final RuleEvaluator alwaysMatch = new RuleEvaluator() {
        @Override public String ruleType() { return "ALWAYS"; }
        @Override public boolean evaluate(RiskRule rule, RiskContext ctx) { return true; }
        @Override public int score(RiskRule rule) { return 50; }
    };

    private final RuleEvaluator neverMatch = new RuleEvaluator() {
        @Override public String ruleType() { return "NEVER"; }
        @Override public boolean evaluate(RiskRule rule, RiskContext ctx) { return false; }
        @Override public int score(RiskRule rule) { return 30; }
    };

    @BeforeEach
    void setUp() {
        service = new RiskRuleService(ruleRepository, List.of(alwaysMatch, neverMatch));
        service.init();
    }

    private RiskContext ctx() {
        return new RiskContext(MERCHANT_ID, 10L, 20L, "1.2.3.4", "dev-1");
    }

    @Nested
    @DisplayName("createRule")
    class CreateRule {

        @Test
        @DisplayName("saves rule and returns DTO with correct fields")
        void savesAndReturnsDTO() {
            RiskRule saved = RiskRule.builder()
                    .id(1L).merchantId(MERCHANT_ID).ruleCode("TEST_RULE")
                    .ruleType("ALWAYS").configJson("{\"score\":50}")
                    .action(RiskAction.BLOCK).active(true).priority(1).build();
            when(ruleRepository.save(any())).thenReturn(saved);

            RiskRuleCreateRequestDTO req = RiskRuleCreateRequestDTO.builder()
                    .merchantId(MERCHANT_ID).ruleCode("TEST_RULE").ruleType("ALWAYS")
                    .configJson("{\"score\":50}").action(RiskAction.BLOCK)
                    .active(true).priority(1).build();

            RiskRuleResponseDTO result = service.createRule(req);

            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.ruleCode()).isEqualTo("TEST_RULE");
            assertThat(result.action()).isEqualTo(RiskAction.BLOCK);

            ArgumentCaptor<RiskRule> captor = ArgumentCaptor.forClass(RiskRule.class);
            verify(ruleRepository).save(captor.capture());
            assertThat(captor.getValue().getMerchantId()).isEqualTo(MERCHANT_ID);
        }
    }

    @Nested
    @DisplayName("listRules")
    class ListRules {

        @Test
        @DisplayName("delegates to repository and maps to DTO")
        void returnsMappedPage() {
            RiskRule rule = RiskRule.builder().id(1L).ruleCode("R").ruleType("ALWAYS")
                    .configJson("{}").action(RiskAction.ALLOW).priority(1).build();
            when(ruleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(rule)));

            Page<RiskRuleResponseDTO> result = service.listRules(Pageable.unpaged());

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).ruleCode()).isEqualTo("R");
        }
    }

    @Nested
    @DisplayName("updateRule")
    class UpdateRule {

        @Test
        @DisplayName("updates all mutable fields and returns updated DTO")
        void updatesFields() {
            RiskRule existing = RiskRule.builder()
                    .id(1L).ruleCode("OLD").ruleType("NEVER")
                    .configJson("{\"score\":10}").action(RiskAction.ALLOW)
                    .active(true).priority(5).build();
            when(ruleRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(ruleRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            RiskRuleCreateRequestDTO req = RiskRuleCreateRequestDTO.builder()
                    .ruleCode("NEW").ruleType("ALWAYS").configJson("{\"score\":99}")
                    .action(RiskAction.BLOCK).active(false).priority(2).build();

            RiskRuleResponseDTO result = service.updateRule(1L, req);

            assertThat(result.ruleCode()).isEqualTo("NEW");
            assertThat(result.action()).isEqualTo(RiskAction.BLOCK);
            assertThat(result.active()).isFalse();
            assertThat(result.priority()).isEqualTo(2);
        }

        @Test
        @DisplayName("throws EntityNotFoundException for unknown rule ID")
        void notFound_throws() {
            when(ruleRepository.findById(999L)).thenReturn(Optional.empty());

            RiskRuleCreateRequestDTO req = RiskRuleCreateRequestDTO.builder()
                    .ruleCode("x").ruleType("ALWAYS").configJson("{}")
                    .action(RiskAction.ALLOW).priority(1).build();

            assertThatThrownBy(() -> service.updateRule(999L, req))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("999");
        }
    }

    @Nested
    @DisplayName("evaluateRules")
    class EvaluateRules {

        @Test
        @DisplayName("matching rule is included in result with its score")
        void matchingRule_inResult() {
            RiskRule rule = RiskRule.builder().id(1L).ruleCode("R1").ruleType("ALWAYS")
                    .configJson("{\"score\":50}").action(RiskAction.BLOCK)
                    .active(true).priority(1).build();
            when(ruleRepository.findActiveRulesForMerchant(MERCHANT_ID)).thenReturn(List.of(rule));

            RiskEvaluationResult result = service.evaluateRules(ctx());

            assertThat(result.matchedRules()).hasSize(1);
            assertThat(result.matchedRules().get(0).getRuleCode()).isEqualTo("R1");
            assertThat(result.totalScore()).isEqualTo(50);
        }

        @Test
        @DisplayName("non-matching rule is excluded from result")
        void nonMatchingRule_excluded() {
            RiskRule rule = RiskRule.builder().id(2L).ruleCode("R2").ruleType("NEVER")
                    .configJson("{\"score\":30}").action(RiskAction.BLOCK)
                    .active(true).priority(1).build();
            when(ruleRepository.findActiveRulesForMerchant(MERCHANT_ID)).thenReturn(List.of(rule));

            RiskEvaluationResult result = service.evaluateRules(ctx());

            assertThat(result.matchedRules()).isEmpty();
            assertThat(result.totalScore()).isZero();
        }

        @Test
        @DisplayName("mixed rules: only matching ones contribute to score")
        void mixedRules_onlyMatchingContribute() {
            RiskRule r1 = RiskRule.builder().id(1L).ruleCode("R1").ruleType("ALWAYS")
                    .configJson("{\"score\":50}").action(RiskAction.BLOCK)
                    .active(true).priority(1).build();
            RiskRule r2 = RiskRule.builder().id(2L).ruleCode("R2").ruleType("NEVER")
                    .configJson("{\"score\":30}").action(RiskAction.REVIEW)
                    .active(true).priority(2).build();
            when(ruleRepository.findActiveRulesForMerchant(MERCHANT_ID)).thenReturn(List.of(r1, r2));

            RiskEvaluationResult result = service.evaluateRules(ctx());

            assertThat(result.matchedRules()).hasSize(1);
            assertThat(result.totalScore()).isEqualTo(50);
        }

        @Test
        @DisplayName("unknown rule type is skipped (no exception)")
        void unknownRuleType_skipped() {
            RiskRule rule = RiskRule.builder().id(3L).ruleCode("R3").ruleType("UNKNOWN_TYPE")
                    .configJson("{}").action(RiskAction.BLOCK)
                    .active(true).priority(1).build();
            when(ruleRepository.findActiveRulesForMerchant(MERCHANT_ID)).thenReturn(List.of(rule));

            assertThatCode(() -> service.evaluateRules(ctx())).doesNotThrowAnyException();
            RiskEvaluationResult result = service.evaluateRules(ctx());
            assertThat(result.matchedRules()).isEmpty();
        }

        @Test
        @DisplayName("no active rules → empty result, score zero")
        void noRules_emptyResult() {
            when(ruleRepository.findActiveRulesForMerchant(MERCHANT_ID)).thenReturn(List.of());

            RiskEvaluationResult result = service.evaluateRules(ctx());

            assertThat(result.matchedRules()).isEmpty();
            assertThat(result.totalScore()).isZero();
        }
    }
}
