package com.firstclub.events;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.replay.EventReplayService;
import com.firstclub.events.replay.ReplayPolicy;
import com.firstclub.events.replay.ReplayRangeRequest;
import com.firstclub.events.replay.ReplayResult;
import com.firstclub.events.replay.ReplaySafetyService;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.events.schema.DomainEventEnvelope;
import com.firstclub.events.schema.PayloadMigrationRegistry;
import com.firstclub.events.schema.PayloadMigrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 13 — Event schema versioning and replay safety.
 *
 * <p>Covers:
 * <ul>
 *   <li>Payload migration: old schema migrated to new schema via PayloadMigrationRegistry</li>
 *   <li>DomainEventEnvelope: payload selection and migration wrapping</li>
 *   <li>ReplaySafetyService: BLOCKED policy, IDEMPOTENT_ONLY guard, loop prevention</li>
 *   <li>EventReplayService: replayed flag set, range pagination, skip propagation</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 13 — Event Schema Versioning and Replay Safety")
class Phase13SchemaVersioningTests {

    // ─────────────────────────────────────────────────────────────────────────
    // PayloadMigrationRegistry
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PayloadMigrationRegistry")
    class PayloadMigrationRegistryTests {

        /** A no-op migrator that appends a marker so we can verify it was called. */
        private static PayloadMigrator migrator(String eventType, int from, String transform) {
            return new PayloadMigrator() {
                public String eventType()   { return eventType; }
                public int    fromVersion() { return from; }
                public int    toVersion()   { return from + 1; }
                public String migrate(String p) { return p + transform; }
            };
        }

        @Test
        @DisplayName("migrates v1 payload to v2 using registered migrator")
        void migratesPayloadFromV1ToV2() {
            PayloadMigrationRegistry registry = new PayloadMigrationRegistry(
                    List.of(migrator("INVOICE_CREATED", 1, "|v2")));

            String result = registry.migrate("{}", "INVOICE_CREATED", 1);

            assertThat(result).isEqualTo("{}|v2");
        }

        @Test
        @DisplayName("chains two migrators for v1→v3 migration")
        void chainsMigratorsAcrossMultipleVersions() {
            PayloadMigrationRegistry registry = new PayloadMigrationRegistry(List.of(
                    migrator("INVOICE_CREATED", 1, "|v2"),
                    migrator("INVOICE_CREATED", 2, "|v3")));

            String result = registry.migrate("{}", "INVOICE_CREATED", 1);

            assertThat(result).isEqualTo("{}|v2|v3");
            assertThat(registry.currentVersion("INVOICE_CREATED")).isEqualTo(3);
        }

        @Test
        @DisplayName("returns payload unchanged when already at current version")
        void noMigrationWhenAlreadyAtCurrentVersion() {
            PayloadMigrationRegistry registry = new PayloadMigrationRegistry(
                    List.of(migrator("INVOICE_CREATED", 1, "|v2")));

            String payload = "{\"amount\":100}";
            String result  = registry.migrate(payload, "INVOICE_CREATED", 2);

            assertThat(result).isSameAs(payload);
        }

        @Test
        @DisplayName("defaults to version 1 for event types with no registered migrators")
        void defaultsToVersion1WhenNoMigratorsRegistered() {
            PayloadMigrationRegistry registry = new PayloadMigrationRegistry(List.of());

            assertThat(registry.currentVersion("UNKNOWN_EVENT")).isEqualTo(1);
            assertThat(registry.needsMigration("UNKNOWN_EVENT", 1)).isFalse();
        }

        @Test
        @DisplayName("throws IllegalStateException when a required migration step is missing")
        void throwsWhenMigrationStepMissing() {
            // Register v2→v3 but NOT v1→v2; migrating from v1 should fail
            PayloadMigrationRegistry registry = new PayloadMigrationRegistry(
                    List.of(migrator("INVOICE_CREATED", 2, "|v3")));

            assertThatThrownBy(() -> registry.migrate("{}", "INVOICE_CREATED", 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No PayloadMigrator");
        }

        @Test
        @DisplayName("throws at startup when duplicate migrator is registered for same version step")
        void throwsAtStartupOnDuplicateMigrator() {
            assertThatThrownBy(() -> new PayloadMigrationRegistry(List.of(
                    migrator("INVOICE_CREATED", 1, "|v2a"),
                    migrator("INVOICE_CREATED", 1, "|v2b"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DomainEventEnvelope
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DomainEventEnvelope")
    class DomainEventEnvelopeTests {

        @Test
        @DisplayName("wraps migrated payload when stored version is stale")
        void wrapsMigratedPayloadWhenStale() {
            PayloadMigrationRegistry registry = new PayloadMigrationRegistry(List.of(
                    new PayloadMigrator() {
                        public String eventType()   { return "INVOICE_CREATED"; }
                        public int    fromVersion() { return 1; }
                        public int    toVersion()   { return 2; }
                        public String migrate(String p) { return p + "|migrated"; }
                    }));

            DomainEvent event = DomainEvent.builder()
                    .id(1L).eventType("INVOICE_CREATED")
                    .payload("{\"original\":true}").schemaVersion(1).build();

            DomainEventEnvelope env = DomainEventEnvelope.wrap(event, registry);

            assertThat(env.wasMigrated()).isTrue();
            assertThat(env.storedSchemaVersion()).isEqualTo(1);
            assertThat(env.currentSchemaVersion()).isEqualTo(2);
            assertThat(env.effectivePayload()).isEqualTo("{\"original\":true}|migrated");
        }

        @Test
        @DisplayName("returns original payload when already at current version")
        void skipsMigrationWhenAtCurrentVersion() {
            PayloadMigrationRegistry registry = new PayloadMigrationRegistry(List.of());

            DomainEvent event = DomainEvent.builder()
                    .id(2L).eventType("PAYMENT_SUCCEEDED")
                    .payload("{\"amount\":500}").schemaVersion(1).build();

            DomainEventEnvelope env = DomainEventEnvelope.wrap(event, registry);

            assertThat(env.wasMigrated()).isFalse();
            assertThat(env.migratedPayload()).isNull();
            assertThat(env.effectivePayload()).isEqualTo("{\"amount\":500}");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ReplaySafetyService
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ReplaySafetyService")
    class ReplaySafetyServiceTests {

        @Mock DomainEventRepository domainEventRepository;
        @InjectMocks ReplaySafetyService replaySafetyService;

        @Test
        @DisplayName("blocks replay of an event that is itself a replay (loop prevention)")
        void blocksReplayOfReplay() {
            DomainEvent replayEvent = DomainEvent.builder()
                    .id(99L).eventType("INVOICE_CREATED")
                    .replayed(true).originalEventId(1L).build();

            var result = replaySafetyService.checkCanReplay(replayEvent);

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("replay chains are not allowed");
        }

        @Test
        @DisplayName("blocks replay when event type policy is BLOCKED")
        void blocksReplayWhenPolicyIsBlocked() {
            DomainEvent event = DomainEvent.builder()
                    .id(5L).eventType("REFUND_ISSUED").replayed(false).build();

            var result = replaySafetyService.checkCanReplay(event);

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("BLOCKED");
            // repository must NOT be queried for BLOCKED events
            verifyNoInteractions(domainEventRepository);
        }

        @Test
        @DisplayName("blocks replay for IDEMPOTENT_ONLY when already replayed once")
        void blocksReplayIdempotentWhenAlreadyReplayed() {
            DomainEvent event = DomainEvent.builder()
                    .id(10L).eventType("INVOICE_CREATED").replayed(false).build();
            when(domainEventRepository.existsByOriginalEventId(10L)).thenReturn(true);

            var result = replaySafetyService.checkCanReplay(event);

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("already been replayed");
        }

        @Test
        @DisplayName("allows replay for IDEMPOTENT_ONLY when event has not been replayed yet")
        void allowsReplayIdempotentWhenNotYetReplayed() {
            DomainEvent event = DomainEvent.builder()
                    .id(11L).eventType("INVOICE_CREATED").replayed(false).build();
            when(domainEventRepository.existsByOriginalEventId(11L)).thenReturn(false);

            var result = replaySafetyService.checkCanReplay(event);

            assertThat(result.allowed()).isTrue();
        }

        @Test
        @DisplayName("allows replay without restriction for ALLOW policy")
        void allowsReplayWhenPolicyIsAllow() {
            DomainEvent event = DomainEvent.builder()
                    .id(20L).eventType("SUBSCRIPTION_CREATED").replayed(false).build();

            var result = replaySafetyService.checkCanReplay(event);

            assertThat(result.allowed()).isTrue();
            assertThat(replaySafetyService.policyFor("SUBSCRIPTION_CREATED"))
                    .isEqualTo(ReplayPolicy.ALLOW);
        }

        @Test
        @DisplayName("defaults to ALLOW policy for unregistered event types")
        void defaultsToAllowForUnknownEventType() {
            assertThat(replaySafetyService.policyFor("UNKNOWN_EVENT_TYPE"))
                    .isEqualTo(ReplayPolicy.ALLOW);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EventReplayService
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EventReplayService")
    class EventReplayServiceTests {

        @Mock DomainEventRepository    domainEventRepository;
        @Mock PayloadMigrationRegistry migrationRegistry;
        @Mock ReplaySafetyService      replaySafetyService;
        @InjectMocks EventReplayService eventReplayService;

        @Test
        @DisplayName("persists replay event with replayed=true and originalEventId set")
        void setsReplayedFlagAndOriginalEventId() {
            DomainEvent original = DomainEvent.builder()
                    .id(1L).eventType("SUBSCRIPTION_CREATED")
                    .payload("{\"subId\":42}").schemaVersion(1)
                    .replayed(false).build();

            when(domainEventRepository.findById(1L)).thenReturn(Optional.of(original));
            when(replaySafetyService.checkCanReplay(original))
                    .thenReturn(ReplaySafetyService.ReplaySafetyCheckResult.ok());
            when(migrationRegistry.currentVersion("SUBSCRIPTION_CREATED")).thenReturn(1);
            when(domainEventRepository.save(any())).thenAnswer(inv -> {
                DomainEvent e = inv.getArgument(0);
                // simulate DB-assigned ID
                return DomainEvent.builder()
                        .id(99L)
                        .eventType(e.getEventType())
                        .payload(e.getPayload())
                        .schemaVersion(e.getSchemaVersion())
                        .replayed(e.isReplayed())
                        .replayedAt(e.getReplayedAt())
                        .originalEventId(e.getOriginalEventId())
                        .build();
            });

            ReplayResult result = eventReplayService.replay(1L);

            assertThat(result.replayed()).isTrue();
            assertThat(result.originalEventId()).isEqualTo(1L);
            assertThat(result.replayEventId()).isEqualTo(99L);

            ArgumentCaptor<DomainEvent> cap = ArgumentCaptor.forClass(DomainEvent.class);
            verify(domainEventRepository).save(cap.capture());
            DomainEvent saved = cap.getValue();
            assertThat(saved.isReplayed()).isTrue();
            assertThat(saved.getOriginalEventId()).isEqualTo(1L);
            assertThat(saved.getReplayedAt()).isNotNull();
        }

        @Test
        @DisplayName("replay does not duplicate side effects — skipped when safety check fails")
        void replayDoesNotDuplicateSideEffects() {
            DomainEvent original = DomainEvent.builder()
                    .id(5L).eventType("REFUND_ISSUED").payload("{}").schemaVersion(1)
                    .replayed(false).build();

            when(domainEventRepository.findById(5L)).thenReturn(Optional.of(original));
            when(replaySafetyService.checkCanReplay(original))
                    .thenReturn(ReplaySafetyService.ReplaySafetyCheckResult.blocked(
                            "BLOCKED replay policy"));

            ReplayResult result = eventReplayService.replay(5L);

            assertThat(result.replayed()).isFalse();
            assertThat(result.skipReason()).contains("BLOCKED");
            verify(domainEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("replay range returns one result per matched event")
        void replayRangeReturnsOneResultPerEvent() {
            LocalDateTime from = LocalDateTime.now().minusHours(1);
            LocalDateTime to   = LocalDateTime.now();

            DomainEvent e1 = DomainEvent.builder()
                    .id(1L).eventType("SUBSCRIPTION_CREATED").payload("{}")
                    .schemaVersion(1).replayed(false).build();
            DomainEvent e2 = DomainEvent.builder()
                    .id(2L).eventType("SUBSCRIPTION_CREATED").payload("{}")
                    .schemaVersion(1).replayed(false).build();

            when(domainEventRepository.findWithFilters(
                    isNull(), eq("SUBSCRIPTION_CREATED"), isNull(), isNull(),
                    eq(from), eq(to), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(e1, e2)));

            when(replaySafetyService.checkCanReplay(any()))
                    .thenReturn(ReplaySafetyService.ReplaySafetyCheckResult.ok());
            when(migrationRegistry.currentVersion(anyString())).thenReturn(1);
            when(domainEventRepository.save(any())).thenAnswer(inv -> {
                DomainEvent e = inv.getArgument(0);
                return DomainEvent.builder().id(100L + e.getOriginalEventId())
                        .eventType(e.getEventType()).payload(e.getPayload())
                        .schemaVersion(e.getSchemaVersion())
                        .replayed(e.isReplayed()).replayedAt(e.getReplayedAt())
                        .originalEventId(e.getOriginalEventId()).build();
            });

            ReplayRangeRequest request = new ReplayRangeRequest(from, to, "SUBSCRIPTION_CREATED", null);
            List<ReplayResult> results = eventReplayService.replayRange(request);

            assertThat(results).hasSize(2);
            assertThat(results).allMatch(ReplayResult::replayed);
            assertThat(results.stream().map(ReplayResult::originalEventId).toList())
                    .containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("replay range includes skipped events with skip reason (subset processed)")
        void replayRangeIncludesSkippedEvents() {
            LocalDateTime from = LocalDateTime.now().minusHours(1);
            LocalDateTime to   = LocalDateTime.now();

            DomainEvent allowEvent   = DomainEvent.builder().id(1L).eventType("SUBSCRIPTION_CREATED")
                    .payload("{}").schemaVersion(1).replayed(false).build();
            DomainEvent blockedEvent = DomainEvent.builder().id(2L).eventType("REFUND_ISSUED")
                    .payload("{}").schemaVersion(1).replayed(false).build();

            when(domainEventRepository.findWithFilters(
                    isNull(), isNull(), isNull(), isNull(), eq(from), eq(to), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(allowEvent, blockedEvent)));

            when(replaySafetyService.checkCanReplay(allowEvent))
                    .thenReturn(ReplaySafetyService.ReplaySafetyCheckResult.ok());
            when(replaySafetyService.checkCanReplay(blockedEvent))
                    .thenReturn(ReplaySafetyService.ReplaySafetyCheckResult.blocked("BLOCKED replay policy"));

            when(migrationRegistry.currentVersion(anyString())).thenReturn(1);
            when(domainEventRepository.save(any())).thenAnswer(inv -> {
                DomainEvent e = inv.getArgument(0);
                return DomainEvent.builder().id(999L).eventType(e.getEventType())
                        .payload(e.getPayload()).schemaVersion(e.getSchemaVersion())
                        .replayed(e.isReplayed()).replayedAt(e.getReplayedAt())
                        .originalEventId(e.getOriginalEventId()).build();
            });

            ReplayRangeRequest request = new ReplayRangeRequest(from, to, null, null);
            List<ReplayResult> results = eventReplayService.replayRange(request);

            assertThat(results).hasSize(2);
            assertThat(results.stream().filter(ReplayResult::replayed).count()).isEqualTo(1);
            assertThat(results.stream().filter(r -> !r.replayed()).findFirst())
                    .hasValueSatisfying(r -> assertThat(r.skipReason()).contains("BLOCKED"));
        }
    }
}
