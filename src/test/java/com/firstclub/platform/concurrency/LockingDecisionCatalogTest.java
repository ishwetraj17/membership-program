package com.firstclub.platform.concurrency;

import com.firstclub.platform.concurrency.locking.LockingDecision;
import com.firstclub.platform.concurrency.locking.LockingDecisionCatalog;
import com.firstclub.platform.concurrency.locking.LockingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link LockingDecisionCatalog} and the {@link LockingDecision} record.
 *
 * All tests are pure unit tests — no Spring context required.
 */
@DisplayName("LockingDecisionCatalog")
class LockingDecisionCatalogTest {

    private LockingDecisionCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new LockingDecisionCatalog();
    }

    // =========================================================================
    // Catalog entries — expected operations and strategies
    // =========================================================================

    @Nested
    @DisplayName("Catalog entries — expected operations and strategies")
    class CatalogEntries {

        @Test
        @DisplayName("subscription.renewal → DISTRIBUTED_FENCE")
        void subscriptionRenewal() {
            assertDecision("subscription.renewal", LockingStrategy.DISTRIBUTED_FENCE);
        }

        @Test
        @DisplayName("refund.create → PESSIMISTIC_FOR_UPDATE")
        void refundCreate() {
            assertDecision("refund.create", LockingStrategy.PESSIMISTIC_FOR_UPDATE);
        }

        @Test
        @DisplayName("dispute.open → PESSIMISTIC_FOR_UPDATE")
        void disputeOpen() {
            assertDecision("dispute.open", LockingStrategy.PESSIMISTIC_FOR_UPDATE);
        }

        @Test
        @DisplayName("invoice_sequence.increment → PESSIMISTIC_FOR_UPDATE")
        void invoiceSequenceIncrement() {
            assertDecision("invoice_sequence.increment", LockingStrategy.PESSIMISTIC_FOR_UPDATE);
        }

        @Test
        @DisplayName("outbox.poll → SKIP_LOCKED")
        void outboxPoll() {
            assertDecision("outbox.poll", LockingStrategy.SKIP_LOCKED);
        }

        @Test
        @DisplayName("scheduler.singleton → ADVISORY")
        void schedulerSingleton() {
            assertDecision("scheduler.singleton", LockingStrategy.ADVISORY);
        }

        @Test
        @DisplayName("projection.update → IDEMPOTENT_ASYNC")
        void projectionUpdate() {
            assertDecision("projection.update", LockingStrategy.IDEMPOTENT_ASYNC);
        }
    }

    // =========================================================================
    // Catalog structure and contract
    // =========================================================================

    @Nested
    @DisplayName("Catalog structure and contract")
    class CatalogStructure {

        @Test
        @DisplayName("allDecisions() returns exactly 7 entries")
        void allDecisions_hasSevenEntries() {
            assertThat(catalog.allDecisions()).hasSize(7);
        }

        @Test
        @DisplayName("allDecisions() is unmodifiable")
        void allDecisions_isUnmodifiable() {
            Map<String, LockingDecision> decisions = catalog.allDecisions();
            assertThatThrownBy(() -> decisions.put("x", null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("forOperation() returns empty for unknown operations")
        void forOperation_unknownReturnsEmpty() {
            assertThat(catalog.forOperation("unknown.operation")).isEmpty();
            assertThat(catalog.forOperation("")).isEmpty();
        }

        @Test
        @DisplayName("every LockingDecision has non-blank rationale")
        void allDecisions_haveNonBlankRationale() {
            catalog.allDecisions().values()
                    .forEach(d -> assertThat(d.rationale())
                            .as("rationale for '%s'", d.domainOperation())
                            .isNotBlank());
        }

        @Test
        @DisplayName("domainOperation field in each decision matches the catalog key")
        void allDecisions_operationMatchesKey() {
            catalog.allDecisions().forEach((key, decision) ->
                    assertThat(decision.domainOperation())
                            .as("domainOperation for key '%s'", key)
                            .isEqualTo(key));
        }

        @Test
        @DisplayName("all LockingStrategy enum values cover the expected taxonomy")
        void lockingStrategy_allValuesPresent() {
            assertThat(LockingStrategy.values())
                    .extracting(Enum::name)
                    .containsExactlyInAnyOrder(
                            "OPTIMISTIC",
                            "DISTRIBUTED_FENCE",
                            "ADVISORY",
                            "PESSIMISTIC_FOR_UPDATE",
                            "SKIP_LOCKED",
                            "SERIALIZABLE",
                            "IDEMPOTENT_ASYNC");
        }
    }

    // =========================================================================
    // LockingDecision record
    // =========================================================================

    @Nested
    @DisplayName("LockingDecision record")
    class LockingDecisionRecord {

        @Test
        @DisplayName("forOperation returns decision with correct fields")
        void forOperation_fieldsPopulated() {
            Optional<LockingDecision> decision = catalog.forOperation("outbox.poll");

            assertThat(decision).isPresent();
            assertThat(decision.get().domainOperation()).isEqualTo("outbox.poll");
            assertThat(decision.get().strategy()).isEqualTo(LockingStrategy.SKIP_LOCKED);
            assertThat(decision.get().rationale()).isNotBlank();
        }

        @Test
        @DisplayName("LockingDecision is a value type — equal instances are equal")
        void lockingDecision_equality() {
            LockingDecision a = new LockingDecision("x.op", LockingStrategy.OPTIMISTIC, "reason");
            LockingDecision b = new LockingDecision("x.op", LockingStrategy.OPTIMISTIC, "reason");

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertDecision(String operation, LockingStrategy expectedStrategy) {
        Optional<LockingDecision> decision = catalog.forOperation(operation);
        assertThat(decision)
                .as("Expected catalogued decision for operation '%s'", operation)
                .isPresent();
        assertThat(decision.get().strategy())
                .as("Strategy for operation '%s'", operation)
                .isEqualTo(expectedStrategy);
    }
}
