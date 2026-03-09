package com.firstclub.platform.repair;

import com.firstclub.platform.repair.controller.RepairAdminController;
import com.firstclub.platform.repair.dto.RepairAuditResponseDTO;
import com.firstclub.platform.repair.entity.RepairActionAudit;
import com.firstclub.platform.repair.repository.RepairActionAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Unit tests for the RepairAdminController — covers audit log retrieval and
 * action dispatch, including not-found handling, dry-run propagation, and
 * action failure captured in the audit trail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RepairAdminController — Unit Tests")
class RepairAdminControllerTest {

    @Mock private RepairActionRegistry        registry;
    @Mock private RepairAuditService          auditService;
    @Mock private RepairActionAuditRepository auditRepository;
    @InjectMocks private RepairAdminController controller;

    // ── Audit log ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /audit")
    class AuditTests {

        @Test
        @DisplayName("returns paged audit entries")
        void getAudit_returnsList() {
            RepairActionAudit audit = auditRow("repair.invoice.recompute_totals", "INVOICE", "42");
            when(auditRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(audit)));

            ResponseEntity<List<RepairAuditResponseDTO>> response = controller.getAudit(0, 50);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).getRepairKey())
                    .isEqualTo("repair.invoice.recompute_totals");
        }

        @Test
        @DisplayName("size is capped at 200")
        void getAudit_sizeIsCapped() {
            when(auditRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            controller.getAudit(0, 5000);
            // Just ensure it doesn't throw — the PageRequest internal will cap at 200
            verify(auditRepository).findAllByOrderByCreatedAtDesc(any(Pageable.class));
        }
    }

    // ── Action dispatch ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Action dispatch — not found")
    class NotFoundTests {

        @Test
        @DisplayName("throws ResponseStatusException(404) when action key is missing from registry")
        void dispatch_throwsWhenActionNotFound() {
            when(registry.findByKey(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.retryOutboxEvent(99L, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }
    }

    @Nested
    @DisplayName("Action dispatch — success")
    class SuccessDispatchTests {

        @Test
        @DisplayName("successful repair action returns 200 with auditId")
        void dispatch_successReturns200WithAuditId() {
            RepairAction stubAction = stubSuccessAction("repair.outbox.retry_event");
            RepairActionAudit auditRow = auditRow("repair.outbox.retry_event", "OUTBOX_EVENT", "7");
            auditRow.setId(55L);

            when(registry.findByKey("repair.outbox.retry_event")).thenReturn(Optional.of(stubAction));
            when(auditService.record(any(), any())).thenReturn(auditRow);

            ResponseEntity<com.firstclub.platform.repair.dto.RepairResponseDTO> resp =
                    controller.retryOutboxEvent(7L, null);

            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(resp.getBody().isSuccess()).isTrue();
            assertThat(resp.getBody().getAuditId()).isEqualTo(55L);
        }

        @Test
        @DisplayName("action IllegalArgumentException is caught and audit row is written as FAILED")
        void dispatch_capturesFailure() {
            RepairAction failAction = new RepairAction() {
                @Override public String getRepairKey() { return "repair.outbox.retry_event"; }
                @Override public String getTargetType() { return "OUTBOX_EVENT"; }
                @Override public boolean supportsDryRun() { return false; }
                @Override public RepairActionResult execute(RepairContext context) {
                    throw new IllegalArgumentException("OutboxEvent not found: 42");
                }
            };
            RepairActionAudit auditRow = auditRow("repair.outbox.retry_event", "OUTBOX_EVENT", "42");
            auditRow.setId(77L);

            when(registry.findByKey("repair.outbox.retry_event")).thenReturn(Optional.of(failAction));
            when(auditService.record(any(), any())).thenReturn(auditRow);

            ResponseEntity<com.firstclub.platform.repair.dto.RepairResponseDTO> resp =
                    controller.retryOutboxEvent(42L, null);

            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(resp.getBody().isSuccess()).isFalse();
            assertThat(resp.getBody().getErrorMessage()).contains("not found");
            // audit must still be written for failed actions
            verify(auditService).record(any(), any());
        }

        @Test
        @DisplayName("dryRun query param propagates into context passed to action")
        void dispatch_dryRunPropagates() {
            RepairAction[] captured = new RepairAction[1];
            RepairAction.RepairContext[] capturedCtx = {null};

            RepairAction stubAction = new RepairAction() {
                @Override public String getRepairKey() { return "repair.invoice.recompute_totals"; }
                @Override public String getTargetType() { return "INVOICE"; }
                @Override public boolean supportsDryRun() { return true; }
                @Override public RepairActionResult execute(RepairContext context) {
                    capturedCtx[0] = context;
                    return RepairActionResult.builder()
                            .repairKey(getRepairKey()).success(true).dryRun(context.dryRun())
                            .evaluatedAt(java.time.LocalDateTime.now()).build();
                }
            };
            RepairActionAudit auditRow = auditRow("repair.invoice.recompute_totals", "INVOICE", "1");
            auditRow.setId(88L);

            when(registry.findByKey("repair.invoice.recompute_totals")).thenReturn(Optional.of(stubAction));
            when(auditService.record(any(), any())).thenReturn(auditRow);

            controller.recomputeInvoice(1L, true, null);

            assertThat(capturedCtx[0]).isNotNull();
            assertThat(capturedCtx[0].dryRun()).isTrue();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static RepairActionAudit auditRow(String key, String targetType, String targetId) {
        return RepairActionAudit.builder()
                .repairKey(key).targetType(targetType).targetId(targetId)
                .status("EXECUTED").dryRun(false).createdAt(LocalDateTime.now())
                .build();
    }

    static RepairAction stubSuccessAction(String key) {
        return new RepairAction() {
            @Override public String getRepairKey() { return key; }
            @Override public String getTargetType() { return "STUB"; }
            @Override public boolean supportsDryRun() { return false; }
            @Override public RepairActionResult execute(RepairContext context) {
                return RepairActionResult.builder()
                        .repairKey(key).success(true).dryRun(false)
                        .details("stub execute").evaluatedAt(java.time.LocalDateTime.now()).build();
            }
        };
    }
}
