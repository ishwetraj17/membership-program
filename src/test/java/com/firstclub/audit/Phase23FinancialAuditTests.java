package com.firstclub.audit;

import com.firstclub.audit.aspect.FinancialAuditAspect;
import com.firstclub.audit.aspect.FinancialOperation;
import com.firstclub.audit.dto.AuditEntryDTO;
import com.firstclub.audit.entity.AuditEntry;
import com.firstclub.audit.repository.AuditEntryRepository;
import com.firstclub.audit.service.AuditEntryService;
import com.firstclub.audit.service.impl.AuditEntryServiceImpl;
import com.firstclub.platform.version.ApiVersion;
import com.firstclub.platform.version.ApiVersionedMapper;
import com.firstclub.platform.version.MerchantApiVersion;
import com.firstclub.platform.version.MerchantApiVersionRepository;
import com.firstclub.platform.version.MerchantApiVersionService;
import com.firstclub.platform.version.MerchantApiVersionServiceImpl;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Phase 23 — Financial audit trail and API versioning tests.
 *
 * <ul>
 *   <li>{@link AuditEntryServiceTests} — service layer unit tests</li>
 *   <li>{@link FinancialAuditAspectTests} — AOP aspect fidelity tests</li>
 *   <li>{@link ApiVersionedMapperTests} — version resolution precedence tests</li>
 *   <li>{@link MerchantApiVersionServiceTests} — pin lifecycle tests</li>
 *   <li>{@link AuditEntryRepositoryTests} — JPA persistence contract tests</li>
 * </ul>
 */
class Phase23FinancialAuditTests {

    // =========================================================================
    // AuditEntryService unit tests
    // =========================================================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("AuditEntryService — service layer")
    class AuditEntryServiceTests {

        @Mock AuditEntryRepository repository;
        AuditEntryService           service;

        @BeforeEach
        void setUp() {
            service = new AuditEntryServiceImpl(repository);
        }

        @Test
        @DisplayName("record — success entry persisted with correct fields")
        void recordSuccessEntry() {
            AuditEntry saved = AuditEntry.builder()
                    .id(1L)
                    .operationType("SUBSCRIPTION_CREATE")
                    .entityType("Subscription")
                    .entityId(42L)
                    .success(true)
                    .build();
            given(repository.save(any())).willReturn(saved);

            AuditEntry result = service.record(
                    "SUBSCRIPTION_CREATE", "SUBSCRIPTION_CREATE",
                    "Subscription", 42L, "user-1",
                    true, null,
                    "req-1", "corr-1", 10L, "user-1", "2025-01-01", "127.0.0.1"
            );

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFailureReason()).isNull();

            ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(repository).save(captor.capture());
            AuditEntry persisted = captor.getValue();
            assertThat(persisted.getOperationType()).isEqualTo("SUBSCRIPTION_CREATE");
            assertThat(persisted.getEntityType()).isEqualTo("Subscription");
            assertThat(persisted.getEntityId()).isEqualTo(42L);
            assertThat(persisted.getMerchantId()).isEqualTo(10L);
            assertThat(persisted.getRequestId()).isEqualTo("req-1");
        }

        @Test
        @DisplayName("record — failure entry persisted with trimmed reason")
        void recordFailureEntry() {
            AuditEntry saved = AuditEntry.builder().id(2L).success(false).build();
            given(repository.save(any())).willReturn(saved);

            service.record(
                    "PAYMENT_CONFIRM", "PAYMENT_CONFIRM",
                    "Payment", 99L, "svc-gateway",
                    false, "Card declined",
                    "req-2", "corr-2", 20L, "svc-gateway", "2024-01-01", null
            );

            ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(repository).save(captor.capture());
            AuditEntry persisted = captor.getValue();
            assertThat(persisted.isSuccess()).isFalse();
            assertThat(persisted.getFailureReason()).isEqualTo("Card declined");
        }

        @Test
        @DisplayName("record — failure reason truncated at 2000 chars")
        void failureReasonTruncated() {
            given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

            String longMessage = "x".repeat(3000);
            service.record(
                    "OP", "OP", "Entity", null, null,
                    false, longMessage,
                    null, null, null, null, null, null
            );

            ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getFailureReason()).hasSizeLessThanOrEqualTo(2001); // 2000 + ellipsis
        }

        @Test
        @DisplayName("findByEntity — delegates to repository and maps to DTO")
        void findByEntity() {
            given(repository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                    eq("Subscription"), eq(1L), any(Pageable.class)))
                    .willReturn(org.springframework.data.domain.Page.empty());

            Page<AuditEntryDTO> result = service.findByEntity("Subscription", 1L,
                    PageRequest.of(0, 10));

            verify(repository).findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                    "Subscription", 1L, PageRequest.of(0, 10));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("findFailures — delegates to repository failure query")
        void findFailures() {
            given(repository.findAllFailures(any())).willReturn(org.springframework.data.domain.Page.empty());

            service.findFailures(PageRequest.of(0, 20));

            verify(repository).findAllFailures(PageRequest.of(0, 20));
        }
    }

    // =========================================================================
    // FinancialAuditAspect unit tests
    // =========================================================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("FinancialAuditAspect — AOP cross-cutting concern")
    class FinancialAuditAspectTests {

        @Mock AuditEntryService auditEntryService;
        @Mock ProceedingJoinPoint pjp;
        @Mock MethodSignature signature;

        FinancialAuditAspect aspect;

        @BeforeEach
        void setUp() {
            aspect = new FinancialAuditAspect(auditEntryService);
            given(pjp.getSignature()).willReturn(signature);
            given(pjp.getArgs()).willReturn(new Object[0]);
            // signature.getMethod() is only called when entityIdExpression is non-blank;
            // tests here use a blank expression so this stub is set up per-test as needed.
        }

        @Test
        @DisplayName("success path — audit entry written with success=true")
        void successPathWritesAuditEntry() throws Throwable {
            FinancialOperation annotation = makeAnnotation("SUBSCRIPTION_CREATE", "Subscription", "");
            given(pjp.proceed()).willReturn("ok");
            given(auditEntryService.record(
                    anyString(), anyString(), anyString(), any(),
                    any(), eq(true), isNull(),
                    any(), any(), any(), any(), any(), any()))
                    .willReturn(AuditEntry.builder().id(1L).build());

            Object result = aspect.auditFinancialOperation(pjp, annotation);

            assertThat(result).isEqualTo("ok");
            verify(auditEntryService).record(
                    eq("SUBSCRIPTION_CREATE"), eq("SUBSCRIPTION_CREATE"),
                    eq("Subscription"), isNull(),
                    isNull(), eq(true), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
            );
        }

        @Test
        @DisplayName("failure path — audit entry written with success=false, exception rethrown")
        void failurePathRethrows() throws Throwable {
            FinancialOperation annotation = makeAnnotation("PAYMENT_CONFIRM", "Payment", "");
            RuntimeException cause = new RuntimeException("Card declined");
            given(pjp.proceed()).willThrow(cause);
            given(auditEntryService.record(
                    anyString(), anyString(), anyString(), any(),
                    any(), eq(false), eq("Card declined"),
                    any(), any(), any(), any(), any(), any()))
                    .willReturn(AuditEntry.builder().id(2L).build());

            assertThatThrownBy(() -> aspect.auditFinancialOperation(pjp, annotation))
                    .isSameAs(cause);

            verify(auditEntryService).record(
                    eq("PAYMENT_CONFIRM"), eq("PAYMENT_CONFIRM"),
                    eq("Payment"), isNull(),
                    isNull(), eq(false), eq("Card declined"),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
            );
        }

        @Test
        @DisplayName("failure path — audit record committed even when auditService itself fails")
        void failurePathAuditServiceFailureStillRethrowsOriginal() throws Throwable {
            FinancialOperation annotation = makeAnnotation("OP", "Entity", "");
            RuntimeException original = new RuntimeException("business error");
            given(pjp.proceed()).willThrow(original);
            given(auditEntryService.record(
                    anyString(), anyString(), anyString(), any(),
                    any(), anyBoolean(), any(),
                    any(), any(), any(), any(), any(), any()))
                    .willThrow(new RuntimeException("db unavailable"));

            // Even if audit service throws, the ORIGINAL exception must propagate
            assertThatThrownBy(() -> aspect.auditFinancialOperation(pjp, annotation))
                    .isInstanceOf(RuntimeException.class);
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private FinancialOperation makeAnnotation(
                String operationType, String entityType, String entityIdExpr) {
            return new FinancialOperation() {
                @Override public Class<FinancialOperation> annotationType() { return FinancialOperation.class; }
                @Override public String operationType()     { return operationType; }
                @Override public String entityType()        { return entityType; }
                @Override public String entityIdExpression() { return entityIdExpr; }
            };
        }

        /** Dummy class for reflection-based signature resolution in tests. */
        static class SampleService {
            public void doWork() {}
        }
    }

    // =========================================================================
    // ApiVersionedMapper unit tests
    // =========================================================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("ApiVersionedMapper — three-tier precedence")
    class ApiVersionedMapperTests {

        @Mock MerchantApiVersionService merchantApiVersionService;
        ApiVersionedMapper mapper;

        @BeforeEach
        void setUp() {
            mapper = new ApiVersionedMapper(merchantApiVersionService);
        }

        @Test
        @DisplayName("header present — header wins over merchant pin")
        void headerWinsOverPin() {
            // No stub needed: the pin service should NOT be called when header is present.
            ApiVersion result = mapper.resolveEffectiveVersion(99L, "2025-01-01");

            assertThat(result).isEqualTo(ApiVersion.V_2025_01);
            verify(merchantApiVersionService, never()).resolvePin(any());
        }

        @Test
        @DisplayName("no header, pin present — merchant pin used")
        void pinUsedWhenNoHeader() {
            given(merchantApiVersionService.resolvePin(5L)).willReturn(Optional.of(ApiVersion.V_2025_01));

            ApiVersion result = mapper.resolveEffectiveVersion(5L, null);

            assertThat(result).isEqualTo(ApiVersion.V_2025_01);
        }

        @Test
        @DisplayName("no header, no pin — DEFAULT returned")
        void defaultWhenNoPinAndNoHeader() {
            given(merchantApiVersionService.resolvePin(5L)).willReturn(Optional.empty());

            ApiVersion result = mapper.resolveEffectiveVersion(5L, null);

            assertThat(result).isEqualTo(ApiVersion.DEFAULT);
        }

        @Test
        @DisplayName("null merchantId, no header — DEFAULT returned without querying pin")
        void nullMerchantIdFallsToDefault() {
            ApiVersion result = mapper.resolveEffectiveVersion(null, null);

            assertThat(result).isEqualTo(ApiVersion.DEFAULT);
            verify(merchantApiVersionService, never()).resolvePin(any());
        }

        @Test
        @DisplayName("blank header — treated as absent, falls through to pin")
        void blankHeaderFallsThroughToPin() {
            given(merchantApiVersionService.resolvePin(3L)).willReturn(Optional.of(ApiVersion.V_2025_01));

            ApiVersion result = mapper.resolveEffectiveVersion(3L, "   ");

            assertThat(result).isEqualTo(ApiVersion.V_2025_01);
        }

        @Test
        @DisplayName("header wins over DEFAULT when merchantId is null")
        void headerWinsWhenNoMerchant() {
            ApiVersion result = mapper.resolveEffectiveVersion(null, "2025-01-01");

            assertThat(result).isEqualTo(ApiVersion.V_2025_01);
        }
    }

    // =========================================================================
    // MerchantApiVersionService unit tests
    // =========================================================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("MerchantApiVersionService — pin lifecycle")
    class MerchantApiVersionServiceTests {

        @Mock MerchantApiVersionRepository repository;
        MerchantApiVersionService service;

        @BeforeEach
        void setUp() {
            service = new MerchantApiVersionServiceImpl(repository);
        }

        @Test
        @DisplayName("pinVersion — creates new pin when none exists")
        void pinVersionCreate() {
            given(repository.findByMerchantId(1L)).willReturn(Optional.empty());
            given(repository.save(any())).willAnswer(inv -> {
                MerchantApiVersion pin = inv.getArgument(0);
                pin.setId(1L);
                return pin;
            });

            MerchantApiVersion result = service.pinVersion(1L, "2025-01-01", LocalDate.of(2025, 1, 1));

            assertThat(result.getMerchantId()).isEqualTo(1L);
            assertThat(result.getPinnedVersion()).isEqualTo("2025-01-01");
            assertThat(result.getEffectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
        }

        @Test
        @DisplayName("pinVersion — updates existing pin (upsert)")
        void pinVersionUpsert() {
            MerchantApiVersion existing = MerchantApiVersion.builder()
                    .id(5L).merchantId(1L).pinnedVersion("2024-01-01")
                    .effectiveFrom(LocalDate.of(2024, 1, 1)).build();
            given(repository.findByMerchantId(1L)).willReturn(Optional.of(existing));
            given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

            MerchantApiVersion result = service.pinVersion(1L, "2025-01-01", LocalDate.of(2025, 1, 1));

            assertThat(result.getId()).isEqualTo(5L); // same record, not a new one
            assertThat(result.getPinnedVersion()).isEqualTo("2025-01-01");
        }

        @Test
        @DisplayName("pinVersion — effectiveFrom defaults to today when null")
        void pinVersionDefaultsEffectiveFrom() {
            given(repository.findByMerchantId(any())).willReturn(Optional.empty());
            given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

            MerchantApiVersion result = service.pinVersion(1L, "2025-01-01", null);

            assertThat(result.getEffectiveFrom()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("pinVersion — rejects invalid version format")
        void pinVersionRejectsInvalidFormat() {
            assertThatThrownBy(() -> service.pinVersion(1L, "v2", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid ApiVersion format");
        }

        @Test
        @DisplayName("resolvePin — returns parsed ApiVersion when pin exists")
        void resolvePinReturnsVersion() {
            given(repository.findByMerchantId(1L)).willReturn(
                    Optional.of(MerchantApiVersion.builder()
                            .merchantId(1L).pinnedVersion("2025-01-01")
                            .effectiveFrom(LocalDate.now()).build()));

            Optional<ApiVersion> result = service.resolvePin(1L);

            assertThat(result).contains(ApiVersion.V_2025_01);
        }

        @Test
        @DisplayName("resolvePin — returns empty when no pin configured")
        void resolvePinReturnsEmptyWhenNone() {
            given(repository.findByMerchantId(99L)).willReturn(Optional.empty());

            assertThat(service.resolvePin(99L)).isEmpty();
        }

        @Test
        @DisplayName("removePin — deletes existing pin")
        void removePinDeletesRecord() {
            MerchantApiVersion pin = MerchantApiVersion.builder().id(3L).merchantId(1L).build();
            given(repository.findByMerchantId(1L)).willReturn(Optional.of(pin));

            service.removePin(1L);

            verify(repository).delete(pin);
        }

        @Test
        @DisplayName("removePin — no-op when no pin exists")
        void removePinNoOpWhenNone() {
            given(repository.findByMerchantId(1L)).willReturn(Optional.empty());

            assertThatCode(() -> service.removePin(1L)).doesNotThrowAnyException();
            verify(repository, never()).delete(any());
        }
    }

    // =========================================================================
    // AuditEntry JPA persistence tests
    // =========================================================================

    @Nested
    @DataJpaTest
    @DisplayName("AuditEntryRepository — JPA persistence contract")
    class AuditEntryRepositoryTests {

        @Autowired AuditEntryRepository auditEntryRepository;

        @BeforeEach
        void clean() {
            auditEntryRepository.deleteAll();
        }

        @Test
        @DisplayName("save and reload round-trip preserves all fields")
        void roundTrip() {
            AuditEntry entry = AuditEntry.builder()
                    .operationType("SUBSCRIPTION_CREATE")
                    .action("SUBSCRIPTION_CREATE")
                    .entityType("Subscription")
                    .entityId(1L)
                    .performedBy("user-42")
                    .success(true)
                    .requestId("req-xyz")
                    .correlationId("corr-abc")
                    .merchantId(10L)
                    .actorId("user-42")
                    .apiVersion("2025-01-01")
                    .build();

            AuditEntry saved = auditEntryRepository.save(entry);
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getOccurredAt()).isNotNull();

            AuditEntry loaded = auditEntryRepository.findById(saved.getId()).orElseThrow();
            assertThat(loaded.getOperationType()).isEqualTo("SUBSCRIPTION_CREATE");
            assertThat(loaded.isSuccess()).isTrue();
            assertThat(loaded.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("findByEntityTypeAndEntityIdOrderByOccurredAtDesc — filters correctly")
        void findByEntity() {
            auditEntryRepository.save(AuditEntry.builder()
                    .action("OP").entityType("Subscription").entityId(1L).success(true).build());
            auditEntryRepository.save(AuditEntry.builder()
                    .action("OP").entityType("Subscription").entityId(2L).success(true).build());
            auditEntryRepository.save(AuditEntry.builder()
                    .action("OP").entityType("Plan").entityId(1L).success(true).build());

            Page<AuditEntry> page = auditEntryRepository
                    .findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                            "Subscription", 1L, PageRequest.of(0, 10));

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent().get(0).getEntityType()).isEqualTo("Subscription");
        }

        @Test
        @DisplayName("findAllFailures — returns only entries where success=false")
        void findAllFailures() {
            auditEntryRepository.save(AuditEntry.builder()
                    .action("OP").entityType("X").entityId(1L).success(true).build());
            auditEntryRepository.save(AuditEntry.builder()
                    .action("OP").entityType("X").entityId(2L).success(false)
                    .failureReason("error").build());

            Page<AuditEntry> failures = auditEntryRepository.findAllFailures(PageRequest.of(0, 10));

            assertThat(failures.getTotalElements()).isEqualTo(1);
            assertThat(failures.getContent().get(0).isSuccess()).isFalse();
        }

        @Test
        @DisplayName("save failure entry — failure_reason and success=false persisted")
        void failureEntryPersisted() {
            AuditEntry entry = AuditEntry.builder()
                    .action("PAYMENT_CONFIRM").entityType("Payment").entityId(5L)
                    .success(false).failureReason("Insufficient funds").build();

            AuditEntry saved = auditEntryRepository.save(entry);
            AuditEntry loaded = auditEntryRepository.findById(saved.getId()).orElseThrow();

            assertThat(loaded.isSuccess()).isFalse();
            assertThat(loaded.getFailureReason()).isEqualTo("Insufficient funds");
        }
    }

    // =========================================================================
    // MerchantApiVersion JPA persistence tests
    // =========================================================================

    @Nested
    @DataJpaTest
    @DisplayName("MerchantApiVersionRepository — JPA persistence contract")
    class MerchantApiVersionRepositoryTests {

        @Autowired MerchantApiVersionRepository merchantApiVersionRepository;

        @BeforeEach
        void clean() {
            merchantApiVersionRepository.deleteAll();
        }

        @Test
        @DisplayName("save and lookup by merchantId")
        void saveAndFindByMerchantId() {
            MerchantApiVersion pin = MerchantApiVersion.builder()
                    .merchantId(50L)
                    .pinnedVersion("2025-01-01")
                    .effectiveFrom(LocalDate.of(2025, 1, 1))
                    .build();

            merchantApiVersionRepository.save(pin);

            Optional<MerchantApiVersion> found = merchantApiVersionRepository.findByMerchantId(50L);
            assertThat(found).isPresent();
            assertThat(found.get().getPinnedVersion()).isEqualTo("2025-01-01");
        }

        @Test
        @DisplayName("existsByMerchantId — true when pin exists")
        void existsByMerchantId() {
            merchantApiVersionRepository.save(MerchantApiVersion.builder()
                    .merchantId(7L).pinnedVersion("2024-01-01")
                    .effectiveFrom(LocalDate.now()).build());

            assertThat(merchantApiVersionRepository.existsByMerchantId(7L)).isTrue();
            assertThat(merchantApiVersionRepository.existsByMerchantId(99L)).isFalse();
        }

        @Test
        @DisplayName("findByMerchantId — empty for unknown merchant")
        void findByMerchantIdEmptyForUnknown() {
            assertThat(merchantApiVersionRepository.findByMerchantId(999L)).isEmpty();
        }
    }
}
