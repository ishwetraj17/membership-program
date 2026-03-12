package com.firstclub.platform.db;

import com.firstclub.platform.db.partitioning.PartitionManager;
import com.firstclub.platform.db.rls.RlsTenantContextConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 22 — DB hardening unit tests.
 *
 * <p>All tests are pure unit tests using a mocked {@link JdbcTemplate}. The
 * {@code isPostgres()} method is controlled via Mockito spy to exercise both
 * the "H2 / skip" path and the "PostgreSQL / execute" path without needing a
 * live database.
 *
 * <p>The three nested suites cover:
 * <ol>
 *   <li>PartitionManager — partition name resolution, date math, and
 *       creation delegation to the PL/pgSQL function.</li>
 *   <li>RlsTenantContextConfigurer — SET LOCAL generation, null safety,
 *       clear context, and currentMerchantContext.</li>
 *   <li>DbConstraintExplainer — EXPLAIN plan forwarding, index-used check,
 *       and pg_indexes / pg_tables / pg_inherits queries.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 22: DB Hardening — Partial Indexes, Partitioning, RLS")
class Phase22DbHardeningTests {

    // =========================================================================
    // PartitionManager
    // =========================================================================

    @Nested
    @DisplayName("PartitionManager — partition name resolution and date math")
    class PartitionNameTests {

        @Mock JdbcTemplate jdbcTemplate;
        PartitionManager pm;

        @BeforeEach
        void setUp() {
            pm = new PartitionManager(jdbcTemplate);
        }

        @Test
        @DisplayName("resolvePartitionName formats YYYY_MM with leading zero for single-digit months")
        void resolvePartitionName_leadingZero() {
            assertThat(pm.resolvePartitionName("outbox_events", YearMonth.of(2026, 3)))
                    .isEqualTo("outbox_events_2026_03");
        }

        @Test
        @DisplayName("resolvePartitionName formats December correctly")
        void resolvePartitionName_december() {
            assertThat(pm.resolvePartitionName("domain_events", YearMonth.of(2025, 12)))
                    .isEqualTo("domain_events_2025_12");
        }

        @Test
        @DisplayName("resolvePartitionName handles year rollover at January")
        void resolvePartitionName_januaryRollover() {
            assertThat(pm.resolvePartitionName("audit_entries", YearMonth.of(2027, 1)))
                    .isEqualTo("audit_entries_2027_01");
        }

        @Test
        @DisplayName("ensurePartitionsExist throws for negative monthsAhead")
        void ensurePartitionsExist_negativeMonthsThrows() {
            assertThatThrownBy(() -> pm.ensurePartitionsExist("outbox_events", -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("monthsAhead must be >= 0");
        }

        @Test
        @DisplayName("ensureAllManagedPartitions is a no-op on H2 (isPostgres=false)")
        void ensureAllManagedPartitions_skipsOnH2() {
            PartitionManager pmSpy = spy(pm);
            doReturn(false).when(pmSpy).isPostgres();

            pmSpy.ensureAllManagedPartitions(2);

            // No calls to jdbcTemplate when not on PostgreSQL
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("ensurePartitionsExist is a no-op on H2 when monthsAhead is valid")
        void ensurePartitionsExist_skipsOnH2() {
            PartitionManager pmSpy = spy(pm);
            doReturn(false).when(pmSpy).isPostgres();

            pmSpy.ensurePartitionsExist("outbox_events", 0);

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("MANAGED_TABLES contains all four partition candidates")
        void managedTablesContainsAllFour() {
            assertThat(PartitionManager.MANAGED_TABLES)
                    .containsExactlyInAnyOrder(
                            "outbox_events",
                            "domain_events",
                            "audit_entries",
                            "ops_timeline_events");
        }

        @Test
        @DisplayName("getRecordedPartitions queries partition_management_log ordered by start date")
        void getRecordedPartitions_queriesManagementLog() {
            List<String> expected = List.of("outbox_events_2026_03", "outbox_events_2026_04");
            when(jdbcTemplate.queryForList(
                    contains("partition_management_log"),
                    eq(String.class),
                    eq("outbox_events")))
                    .thenReturn(expected);

            List<String> result = pm.getRecordedPartitions("outbox_events");

            assertThat(result).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("PartitionManager — partition creation on PostgreSQL")
    class PartitionCreationTests {

        @Mock JdbcTemplate jdbcTemplate;
        PartitionManager pm;

        @BeforeEach
        void setUp() {
            pm = spy(new PartitionManager(jdbcTemplate));
            doReturn(true).when(pm).isPostgres();
        }

        @Test
        @DisplayName("ensurePartitionsExist with monthsAhead=0 calls create_monthly_partition once")
        void ensurePartitionsExist_withZeroAhead_createsOnePartition() {
            when(jdbcTemplate.queryForObject(
                    eq("SELECT create_monthly_partition(?, ?, ?)"),
                    eq(String.class),
                    eq("outbox_events"), any(), any()))
                    .thenReturn("outbox_events_partition");

            pm.ensurePartitionsExist("outbox_events", 0);

            verify(jdbcTemplate, times(1)).queryForObject(
                    eq("SELECT create_monthly_partition(?, ?, ?)"),
                    eq(String.class),
                    eq("outbox_events"), any(), any());
        }

        @Test
        @DisplayName("ensurePartitionsExist with monthsAhead=2 calls create_monthly_partition 3 times")
        void ensurePartitionsExist_withTwoAhead_createsThreePartitions() {
            when(jdbcTemplate.queryForObject(
                    eq("SELECT create_monthly_partition(?, ?, ?)"),
                    eq(String.class),
                    eq("domain_events"), any(), any()))
                    .thenReturn("domain_events_partition");

            pm.ensurePartitionsExist("domain_events", 2);

            verify(jdbcTemplate, times(3)).queryForObject(
                    eq("SELECT create_monthly_partition(?, ?, ?)"),
                    eq(String.class),
                    eq("domain_events"), any(), any());
        }

        @Test
        @DisplayName("ensureAllManagedPartitions calls ensurePartitionsExist for all 4 managed tables")
        void ensureAllManagedPartitions_callsEnsureForAllTables() {
            doNothing().when(pm).ensurePartitionsExist(anyString(), anyInt());

            pm.ensureAllManagedPartitions(1);

            for (String table : PartitionManager.MANAGED_TABLES) {
                verify(pm).ensurePartitionsExist(table, 1);
            }
        }

        @Test
        @DisplayName("ensurePartitionsExist skips INSERT when create_monthly_partition returns null")
        void ensurePartitionsExist_functionReturnsNull_noInsert() {
            when(jdbcTemplate.queryForObject(
                    eq("SELECT create_monthly_partition(?, ?, ?)"),
                    eq(String.class),
                    any(), any(), any()))
                    .thenReturn(null);

            // null return from function means parent table not partitioned; no management log INSERT
            pm.ensurePartitionsExist("outbox_events", 0);

            verify(jdbcTemplate, never()).update(anyString());
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }
    }

    // =========================================================================
    // RlsTenantContextConfigurer
    // =========================================================================

    @Nested
    @DisplayName("RlsTenantContextConfigurer — SET LOCAL and merchant context")
    class RlsConfigTests {

        @Mock JdbcTemplate jdbcTemplate;
        RlsTenantContextConfigurer rls;

        @BeforeEach
        void setUp() {
            rls = spy(new RlsTenantContextConfigurer(jdbcTemplate));
        }

        @Test
        @DisplayName("applyMerchantContext throws IllegalArgumentException for null merchantId")
        void applyMerchantContext_nullThrows() {
            assertThatThrownBy(() -> rls.applyMerchantContext(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-null positive Long");
        }

        @Test
        @DisplayName("applyMerchantContext throws for negative merchantId")
        void applyMerchantContext_negativeThrows() {
            assertThatThrownBy(() -> rls.applyMerchantContext(-5L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("applyMerchantContext is a no-op on H2 (isPostgres=false)")
        void applyMerchantContext_skipsOnH2() {
            doReturn(false).when(rls).isPostgres();

            rls.applyMerchantContext(42L);

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("applyMerchantContext executes SET LOCAL with correct merchant ID on PostgreSQL")
        void applyMerchantContext_executesSetLocalOnPostgres() {
            doReturn(true).when(rls).isPostgres();

            rls.applyMerchantContext(99L);

            verify(jdbcTemplate).execute("SET LOCAL app.current_merchant_id = '99'");
        }

        @Test
        @DisplayName("SET LOCAL SQL contains the merchant ID as a quoted numeric literal")
        void applyMerchantContext_sqlContainsMerchantId() {
            doReturn(true).when(rls).isPostgres();

            rls.applyMerchantContext(1234L);

            verify(jdbcTemplate).execute(contains("1234"));
        }

        @Test
        @DisplayName("clearMerchantContext is a no-op on H2")
        void clearMerchantContext_skipsOnH2() {
            doReturn(false).when(rls).isPostgres();

            rls.clearMerchantContext();

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("clearMerchantContext sets empty string on PostgreSQL")
        void clearMerchantContext_setsEmptyStringOnPostgres() {
            doReturn(true).when(rls).isPostgres();

            rls.clearMerchantContext();

            verify(jdbcTemplate).execute(contains("app.current_merchant_id = ''"));
        }

        @Test
        @DisplayName("currentMerchantContext returns empty on H2")
        void currentMerchantContext_emptyOnH2() {
            doReturn(false).when(rls).isPostgres();

            assertThat(rls.currentMerchantContext()).isEmpty();
        }

        @Test
        @DisplayName("currentMerchantContext returns the parsed merchant ID from Postgres session")
        void currentMerchantContext_returnsValueOnPostgres() {
            doReturn(true).when(rls).isPostgres();
            when(jdbcTemplate.queryForObject(contains("current_setting"), eq(String.class)))
                    .thenReturn("42");

            assertThat(rls.currentMerchantContext()).contains(42L);
        }

        @Test
        @DisplayName("currentMerchantContext returns empty when session variable is blank")
        void currentMerchantContext_emptyWhenBlank() {
            doReturn(true).when(rls).isPostgres();
            when(jdbcTemplate.queryForObject(contains("current_setting"), eq(String.class)))
                    .thenReturn("");

            assertThat(rls.currentMerchantContext()).isEmpty();
        }
    }

    // =========================================================================
    // DbConstraintExplainer
    // =========================================================================

    @Nested
    @DisplayName("DbConstraintExplainer — EXPLAIN plan and schema introspection")
    class ExplainerTests {

        @Mock JdbcTemplate jdbcTemplate;
        DbConstraintExplainer explainer;

        @BeforeEach
        void setUp() {
            explainer = spy(new DbConstraintExplainer(jdbcTemplate));
        }

        @Test
        @DisplayName("explainQuery returns empty string on H2")
        void explainQuery_emptyOnH2() {
            doReturn(false).when(explainer).isPostgres();

            assertThat(explainer.explainQuery("SELECT 1")).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("isIndexUsed returns false on H2")
        void isIndexUsed_falseOnH2() {
            doReturn(false).when(explainer).isPostgres();

            assertThat(explainer.isIndexUsed("SELECT 1", "any_index")).isFalse();
        }

        @Test
        @DisplayName("isRlsEnabled returns false on H2")
        void isRlsEnabled_falseOnH2() {
            doReturn(false).when(explainer).isPostgres();

            assertThat(explainer.isRlsEnabled("customers")).isFalse();
        }

        @Test
        @DisplayName("listPartitions returns empty list on H2")
        void listPartitions_emptyOnH2() {
            doReturn(false).when(explainer).isPostgres();

            assertThat(explainer.listPartitions("outbox_events")).isEmpty();
        }

        @Test
        @DisplayName("listPartialIndexes returns empty list on H2")
        void listPartialIndexes_emptyOnH2() {
            doReturn(false).when(explainer).isPostgres();

            assertThat(explainer.listPartialIndexes("outbox_events")).isEmpty();
        }

        @Test
        @DisplayName("explainQuery joins plan lines with newline on PostgreSQL")
        void explainQuery_joinsPlanLinesOnPostgres() {
            doReturn(true).when(explainer).isPostgres();
            when(jdbcTemplate.queryForList(startsWith("EXPLAIN"), eq(String.class)))
                    .thenReturn(List.of(
                            "Bitmap Heap Scan on outbox_events",
                            "  Recheck Cond: (status = ANY ('{NEW,FAILED}'::text[]))",
                            "  ->  Bitmap Index Scan on idx_outbox_retryable_by_merchant"));

            String plan = explainer.explainQuery("SELECT * FROM outbox_events WHERE status IN ('NEW','FAILED')");

            assertThat(plan).contains("idx_outbox_retryable_by_merchant");
            assertThat(plan).contains("\n");
        }

        @Test
        @DisplayName("isIndexUsed returns true when index name appears in the EXPLAIN plan")
        void isIndexUsed_trueWhenIndexInPlan() {
            doReturn(true).when(explainer).isPostgres();
            when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of("Index Scan using idx_subscriptions_v2_active_renewal on subscriptions_v2"));

            assertThat(explainer.isIndexUsed(
                    "SELECT * FROM subscriptions_v2 WHERE status='ACTIVE' AND next_billing_at <= NOW()",
                    "idx_subscriptions_v2_active_renewal"))
                    .isTrue();
        }

        @Test
        @DisplayName("isIndexUsed returns false when plan uses a different index")
        void isIndexUsed_falseWhenDifferentIndex() {
            doReturn(true).when(explainer).isPostgres();
            when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of("Seq Scan on subscriptions_v2"));

            assertThat(explainer.isIndexUsed(
                    "SELECT * FROM subscriptions_v2",
                    "idx_subscriptions_v2_active_renewal"))
                    .isFalse();
        }

        @Test
        @DisplayName("isRlsEnabled returns true when pg_tables.rowsecurity is true")
        void isRlsEnabled_trueWhenEnabled() {
            doReturn(true).when(explainer).isPostgres();
            when(jdbcTemplate.queryForObject(contains("rowsecurity"), eq(Boolean.class), eq("customers")))
                    .thenReturn(Boolean.TRUE);

            assertThat(explainer.isRlsEnabled("customers")).isTrue();
        }

        @Test
        @DisplayName("isRlsEnabled returns false when pg_tables.rowsecurity is false")
        void isRlsEnabled_falseWhenDisabled() {
            doReturn(true).when(explainer).isPostgres();
            when(jdbcTemplate.queryForObject(contains("rowsecurity"), eq(Boolean.class), eq("products")))
                    .thenReturn(Boolean.FALSE);

            assertThat(explainer.isRlsEnabled("products")).isFalse();
        }

        @Test
        @DisplayName("listPartialIndexes returns PartialIndexInfo records with indexName and indexDef")
        @SuppressWarnings("unchecked")
        void listPartialIndexes_returnsInfoOnPostgres() {
            doReturn(true).when(explainer).isPostgres();
            when(jdbcTemplate.query(contains("pg_indexes"), any(RowMapper.class), eq("invoices")))
                    .thenReturn(List.of(
                            new DbConstraintExplainer.PartialIndexInfo(
                                    "idx_invoices_unpaid_by_merchant",
                                    "CREATE INDEX idx_invoices_unpaid_by_merchant ON invoices(merchant_id, due_date) WHERE status IN ('OPEN','PAST_DUE')")));

            List<DbConstraintExplainer.PartialIndexInfo> result = explainer.listPartialIndexes("invoices");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).indexName()).isEqualTo("idx_invoices_unpaid_by_merchant");
            assertThat(result.get(0).indexDef()).contains("WHERE");
        }

        @Test
        @DisplayName("listPartitions queries pg_inherits for child partitions on PostgreSQL")
        void listPartitions_queriesPgInheritsOnPostgres() {
            doReturn(true).when(explainer).isPostgres();
            when(jdbcTemplate.queryForList(
                    contains("pg_inherits"),
                    eq(String.class),
                    eq("outbox_events")))
                    .thenReturn(List.of("outbox_events_2026_03", "outbox_events_2026_04"));

            List<String> partitions = explainer.listPartitions("outbox_events");

            assertThat(partitions).containsExactly("outbox_events_2026_03", "outbox_events_2026_04");
        }
    }

    // =========================================================================
    // DbMaintenanceService
    // =========================================================================

    @Nested
    @DisplayName("DbMaintenanceService — scheduled maintenance delegation")
    class MaintenanceServiceTests {

        @Mock PartitionManager partitionManager;
        DbMaintenanceService service;

        @BeforeEach
        void setUp() {
            service = new DbMaintenanceService(partitionManager);
        }

        @Test
        @DisplayName("ensureUpcomingPartitions calls ensureAllManagedPartitions(2)")
        void ensureUpcomingPartitions_callsWithTwoMonthsAhead() {
            service.ensureUpcomingPartitions();

            verify(partitionManager).ensureAllManagedPartitions(2);
        }

        @Test
        @DisplayName("runPartitionMaintenance passes caller-specified monthsAhead to PartitionManager")
        void runPartitionMaintenance_forwardsMonthsAhead() {
            service.runPartitionMaintenance(5);

            verify(partitionManager).ensureAllManagedPartitions(5);
        }
    }
}
