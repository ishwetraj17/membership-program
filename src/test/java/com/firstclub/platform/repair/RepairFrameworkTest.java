package com.firstclub.platform.repair;

import com.firstclub.platform.repair.actions.*;
import com.firstclub.platform.repair.entity.RepairActionAudit;
import com.firstclub.platform.repair.repository.RepairActionAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the repair framework core: registry, audit service, and
 * action contract behaviour.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Repair Framework — Unit Tests")
class RepairFrameworkTest {

    // ── RepairActionRegistry ──────────────────────────────────────────────────

    @Nested
    @DisplayName("RepairActionRegistry")
    class RegistryTests {

        @Test
        @DisplayName("findByKey returns present for known key")
        void findByKey_knownKey() {
            RepairAction action = stubAction("repair.test.key", "TEST");
            RepairActionRegistry registry = new RepairActionRegistry(List.of(action));
            assertThat(registry.findByKey("repair.test.key")).isPresent();
        }

        @Test
        @DisplayName("findByKey returns empty for unknown key")
        void findByKey_unknownKey() {
            RepairActionRegistry registry = new RepairActionRegistry(List.of());
            assertThat(registry.findByKey("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("size reflects number of registered actions")
        void size() {
            RepairAction a1 = stubAction("repair.a.one", "A");
            RepairAction a2 = stubAction("repair.b.two", "B");
            RepairActionRegistry registry = new RepairActionRegistry(List.of(a1, a2));
            assertThat(registry.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("getAll returns all actions")
        void getAll() {
            RepairAction a = stubAction("repair.a.one", "A");
            RepairActionRegistry registry = new RepairActionRegistry(List.of(a));
            assertThat(registry.getAll()).hasSize(1);
        }
    }

    // ── RepairAuditService ────────────────────────────────────────────────────

    @Nested
    @DisplayName("RepairAuditService")
    class AuditServiceTests {

        @Mock private RepairActionAuditRepository auditRepository;
        @Mock private ObjectMapper objectMapper;
        @InjectMocks private RepairAuditService auditService;

        @Test
        @DisplayName("record() persists audit row with correct fields")
        void record_persistsAuditRow() {
            RepairActionAudit saved = RepairActionAudit.builder()
                    .id(99L)
                    .repairKey("repair.invoice.recompute_totals")
                    .targetType("INVOICE")
                    .targetId("42")
                    .status("EXECUTED")
                    .dryRun(false)
                    .createdAt(LocalDateTime.now())
                    .build();
            when(auditRepository.save(any())).thenReturn(saved);

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "42", Map.of(), false, 1L, "test reason");
            RepairActionResult result = RepairActionResult.builder()
                    .repairKey("repair.invoice.recompute_totals")
                    .success(true)
                    .dryRun(false)
                    .details("totals recomputed")
                    .evaluatedAt(LocalDateTime.now())
                    .build();

            RepairActionAudit audit = auditService.record(ctx, result);

            assertThat(audit.getId()).isEqualTo(99L);
            verify(auditRepository).save(any(RepairActionAudit.class));
        }

        @Test
        @DisplayName("record() sets status=FAILED when result.success==false")
        void record_setsFailedStatus() {
            when(auditRepository.save(any())).thenAnswer(inv -> {
                RepairActionAudit a = inv.getArgument(0);
                a.setId(1L);
                return a;
            });

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "5", Map.of(), false, null, null);
            RepairActionResult result = RepairActionResult.builder()
                    .repairKey("repair.invoice.recompute_totals")
                    .success(false)
                    .dryRun(false)
                    .errorMessage("not found")
                    .evaluatedAt(LocalDateTime.now())
                    .build();

            RepairActionAudit audit = auditService.record(ctx, result);
            assertThat(audit.getStatus()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("toJson() returns serialised JSON string")
        void toJson_serialises() throws Exception {
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"id\":1}");
            String json = auditService.toJson(Map.of("id", 1));
            assertThat(json).isEqualTo("{\"id\":1}");
        }
    }

    // ── RepairContext ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RepairAction.RepairContext")
    class RepairContextTests {

        @Test
        @DisplayName("param() returns value from params map")
        void param_returnsValue() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "10", Map.of("date", "2024-03-15"), false, null, null);
            assertThat(ctx.param("date")).isEqualTo("2024-03-15");
        }

        @Test
        @DisplayName("paramOrDefault() returns default when key absent")
        void paramOrDefault_returnsDefault() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    null, Map.of(), false, null, null);
            assertThat(ctx.paramOrDefault("missing", "fallback")).isEqualTo("fallback");
        }

        @Test
        @DisplayName("null params map is coerced to empty map")
        void nullParamsCoerced() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    null, null, false, null, null);
            assertThat(ctx.params()).isEmpty();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static RepairAction stubAction(String key, String targetType) {
        return new RepairAction() {
            @Override public String getRepairKey() { return key; }
            @Override public String getTargetType() { return targetType; }
            @Override public boolean supportsDryRun() { return false; }
            @Override public RepairActionResult execute(RepairContext context) {
                return RepairActionResult.builder()
                        .repairKey(key).success(true).dryRun(false)
                        .evaluatedAt(LocalDateTime.now()).build();
            }
        };
    }
}
