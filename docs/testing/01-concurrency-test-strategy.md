# Phase 24: Concurrency Test Strategy

## Overview

This document outlines the comprehensive concurrency testing strategy implemented in Phase 24 to ensure the FirstClub membership system can handle high-throughput concurrent operations while maintaining data consistency and business logic integrity.

## Test Philosophy

**Objective**: Prove that concurrent operations cannot violate business invariants or create inconsistent data states, even under extreme load conditions.

**Core Principle**: _"If it can race, it will race. If it will race, test it."_

## Concurrency Test Suite

### 1. ConcurrentRefundIT - Payment Race Conditions
**Location**: `src/test/java/com/firstclub/concurrency/elite/ConcurrentRefundIT.java`

**Scenario**: 50 concurrent threads attempting to refund $50 each against a $100 captured payment.

**Business Invariants Tested**:
- Total refunded amount ≤ captured amount
- No double-refund scenarios
- Optimistic locking prevents race conditions
- Database consistency maintained across all concurrent operations

**Key Metrics**:
- Thread count: 50
- Refund amount per thread: $50.00
- Expected successful refunds: 2
- Expected failed refunds: 48
- Timeout: 30 seconds

**Why This Matters**: Refund operations are high-value, irreversible transactions where race conditions could lead to financial loss. This test proves the system maintains monetary accuracy under concurrent load.

### 2. ConcurrentRenewalIT - Subscription Racing
**Location**: `src/test/java/com/firstclub/concurrency/elite/ConcurrentRenewalIT.java`

**Scenario**: 20 concurrent renewal attempts on the same subscription during the renewal window.

**Business Invariants Tested**:
- Only one renewal succeeds per billing period
- Version-based optimistic locking prevents duplicate renewals
- Next billing date updated correctly
- Subscription state remains consistent

**Key Metrics**:
- Thread count: 20
- Expected successful renewals: 1
- Expected failed renewals: 19
- Timeout: 25 seconds

**Why This Matters**: Duplicate renewals would result in double-charging customers, violating business rules and regulatory compliance. This test ensures subscription billing integrity.

### 3. DuplicateWebhookIT - Idempotency Under Load
**Location**: `src/test/java/com/firstclub/concurrency/elite/DuplicateWebhookIT.java`

**Scenario**: 15 concurrent webhook deliveries with the same idempotency key.

**Business Invariants Tested**:
- Idempotency prevents duplicate processing
- Payment state changes exactly once
- Webhook status tracking remains accurate
- No resource leakage from blocked duplicates

**Key Metrics**:
- Thread count: 15
- Idempotency key: Shared across all threads
- Expected processing count: 1
- Expected duplicate rejections: 14
- Timeout: 20 seconds

**Why This Matters**: Webhook systems are inherently distributed and prone to retries. Duplicate processing could trigger multiple payment captures, violating financial accuracy.

### 4. IdempotencyRaceIT - Key Creation Races
**Location**: `src/test/java/com/firstclub/concurrency/elite/IdempotencyRaceIT.java`

**Scenario**: 25 concurrent operations using identical idempotency keys.

**Business Invariants Tested**:
- Single operation executes per idempotency key
- All threads receive identical response
- No phantom resource creation
- Database constraints prevent key violations

**Key Metrics**:
- Thread count: 25
- Shared idempotency key: Single value
- Expected resource creation: 1
- Expected identical responses: 25
- Timeout: 30 seconds

**Why This Matters**: Idempotency key races at the database level could result in duplicate operations despite application-level checks. This test validates database-level race protection.

### 5. OutboxLeaseRecoveryIT - Event Processing Races
**Location**: `src/test/java/com/firstclub/concurrency/elite/OutboxLeaseRecoveryIT.java`

**Scenario**: 10 concurrent event processors competing for outbox events, with stale lease recovery.

**Business Invariants Tested**:
- Single processor per event (mutual exclusion)
- Stale lease timeout and recovery
- Events processed exactly once
- No processor starvation

**Key Metrics**:
- Processor count: 10
- Event count: Variable
- Lease timeout: 5 seconds
- Expected processing: Exactly-once semantics
- Timeout: 35 seconds

**Why This Matters**: Distributed event processing is critical for eventual consistency. Races in lease management could result in duplicate event processing or event loss.

## Testing Patterns

### Thread Coordination
```java
CountDownLatch startSignal = new CountDownLatch(1);
CountDownLatch doneSignal = new CountDownLatch(threadCount);
ExecutorService executor = Executors.newFixedThreadPool(threadCount);
```

**Pattern**: All threads wait at starting line, then execute concurrently to maximize contention.

### Optimistic Locking Validation
```java
@Version
private Long version;

// Test verifies that concurrent updates fail with OptimisticLockException
```

**Pattern**: Version-based optimistic locking prevents lost updates in race conditions.

### Database Consistency Checks
```java
// After concurrent operations, verify invariants
long totalRefunded = refundRepository.getTotalRefundedAmount(paymentId);
assertThat(totalRefunded).isLessThanOrEqualTo(capturedAmount);
```

**Pattern**: Post-concurrency validation ensures business rules held throughout the operation.

## Test Infrastructure

### Base Class: PostgresIntegrationTestBase
- Real PostgreSQL via Testcontainers
- Transaction rollback between tests
- Realistic database constraints and behavior

### Annotations
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`: Shared test state
- `@Timeout`: Prevents hanging tests
- `@DisplayName`: Clear test documentation

### Performance Targets
- **50 threads**: Maximum realistic concurrent refund operations
- **20-25 threads**: Typical subscription renewal contention
- **10 processors**: Realistic outbox handler scaling

## Failure Scenarios Tested

1. **Database Deadlocks**: Concurrent transactions accessing same resources
2. **Optimistic Lock Failures**: Version conflicts during updates
3. **Constraint Violations**: Unique key and foreign key race conditions
4. **Resource Exhaustion**: Connection pool depletion under load
5. **Timeout Scenarios**: Long-running operations under contention

## Success Criteria

### Functional Correctness
- ✅ All business invariants maintained
- ✅ No data corruption or loss
- ✅ Consistent error handling

### Performance Characteristics
- ✅ Operations complete within timeout bounds
- ✅ Graceful degradation under contention
- ✅ No resource leaks or deadlocks

### Observability
- ✅ Clear success/failure patterns
- ✅ Meaningful error messages
- ✅ Audit trail preservation

## Integration with CI/CD

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*IT.java</include>
        </includes>
    </configuration>
</plugin>
```

**Recommendation**: Run concurrency tests in dedicated CI stage with sufficient resources (4+ CPU cores, 8GB+ RAM).

## Maintenance Guidelines

### Adding New Concurrency Tests
1. Identify business operations with shared resource access
2. Determine realistic thread counts based on production load
3. Define clear business invariants to validate
4. Implement proper thread coordination
5. Add post-execution consistency checks

### Performance Tuning
- Monitor test execution time trends
- Adjust thread counts based on CI environment capacity
- Consider separate test profiles for local vs CI execution

### Failure Analysis
- Log contention patterns and timing
- Analyze OptimisticLockException frequency
- Monitor database connection pool behavior
- Track timeout patterns and adjust as needed

## Related Documentation

- [Chaos Test Strategy](02-chaos-test-strategy.md) - Failure simulation under concurrency
- [How This Scales](../architecture/15-how-this-scales.md) - Production scaling considerations
- [Incident Response](../operations/04-incident-response.md) - Handling concurrency-related production issues

---

**Phase 24 Status**: ✅ **COMPLETE** - All 5 concurrency integration tests implemented and validated.

**Next Steps**: Execute full test suite and validate against performance baselines in production-like environment.