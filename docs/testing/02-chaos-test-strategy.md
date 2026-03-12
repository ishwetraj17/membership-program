# Phase 24: Chaos Test Strategy

## Overview

The Chaos Test Strategy validates FirstClub's resilience under real-world failure conditions, proving the system degrades gracefully and recovers predictably when infrastructure, dependencies, or internal components fail.

## Chaos Engineering Philosophy

**Core Principle**: _"Run chaos experiments that break your system in production-like ways, but in a controlled test environment."_

**Objectives**:
- Validate graceful degradation under dependency failures
- Prove system recovery paths work correctly
- Identify single points of failure before they hit production
- Build confidence in system resilience at 500K TPS scale

## Chaos Test Scenarios

### 1. RedisFailureChaosIT - Cache Layer Disruption
**Location**: `src/test/java/com/firstclub/chaos/elite/RedisFailureChaosIT.java`

**Failure Modes Simulated**:
- Connection timeout during cache operations
- Redis cluster partial failure (split-brain scenarios)
- Memory exhaustion causing eviction storms
- Network partition isolating cache layer
- Command execution failures (timeouts, corrupted responses)
- Full cluster failure with recovery simulation

**Resilience Behaviors Validated**:
- ✅ System continues operating without cache (degraded performance)
- ✅ No duplicate operations despite cache failures
- ✅ Automatic failover to database-only mode
- ✅ Cache recovery detection and gradual re-enablement

**Key Test Methods**:
```java
@Test void redisConnectionTimeout_operationsContinueWithDegradedFunctionality()
@Test void redisClusterFailure_systemHandlesPartialAvailability()
@Test void redisMemoryExhaustion_systemDegradesGracefully()
@Test void redisNetworkPartition_systemMaintainsConsistency()
```

**Success Criteria**: Operations succeed at 80%+ rate during Redis outage, with full recovery within 30 seconds of cache restoration.

### 2. OutboxRecoveryChaosIT - Event Processing Resilience
**Location**: `src/test/java/com/firstclub/chaos/elite/OutboxRecoveryChaosIT.java`

**Failure Modes Simulated**:
- Processor crashes mid-processing (orphaned leases)
- Network timeouts during event handling
- Database rollbacks after successful processing
- Memory pressure causing OOM in handlers  
- Poison messages causing consistent failures

**Recovery Behaviors Validated**:
- ✅ Stale lease recovery after processor crash
- ✅ Exponential backoff retry for transient failures
- ✅ Dead letter queue for poison messages
- ✅ Graceful degradation under memory pressure
- ✅ Event replay after rollback scenarios

**Key Test Methods**:
```java
@Test void processorCrashMidProcessing_eventsRecoverAfterLeaseTimeout()
@Test void handlerTimeoutDuringProcessing_eventRetriesWithExponentialBackoff()
@Test void memoryPressureCausingOOM_systemDegradesGracefully()
@Test void poisonMessageDetection_repeatedlyFailingEventsAreQuarantined()
```

**Success Criteria**: Zero event loss, bounded recovery time (<2 minutes), and automatic poison message isolation.

### 3. RollbackCheckpointIT - Transactional Consistency Under Failure
**Location**: `src/test/java/com/firstclub/chaos/elite/RollbackCheckpointIT.java`

**Failure Modes Simulated**:
- Transaction rollback after checkpoint creation
- Partial rollback with mixed checkpoint states
- System crash between idempotency key creation and completion
- Database deadlocks forcing transactional rollback
- Concurrent rollbacks with checkpoint interference

**Consistency Behaviors Validated**:
- ✅ Checkpoints accurately reflect committed state
- ✅ Rolled-back operations can be safely retried
- ✅ No partial state corruption after rollback
- ✅ Idempotency preserved across rollback/retry cycles
- ✅ Deadlock recovery maintains data consistency

**Key Test Methods**:
```java
@Test void transactionRollbackAfterCheckpointCreation_checkpointsAreCleanedUp()
@Test void partialRollbackWithMixedCheckpointStates_systemDetectsInconsistency()
@Test void systemCrashBetweenIdempotencyKeyAndCompletion_operationCanBeRetriedSafely()
@Test void databaseDeadlockForcingRollback_checkpointsHandleDeadlockRecovery()
```

**Success Criteria**: All checkpoints reflect actual committed state; no phantom or partial checkpoints survive rollbacks.

### 4. UnknownGatewayOutcomeIT - Payment Uncertainty Resolution
**Location**: `src/test/java/com/firstclub/chaos/elite/UnknownGatewayOutcomeIT.java`

**Failure Modes Simulated**:
- Gateway timeout during payment processing (no response)
- Network isolation causing request/response loss
- Gateway returns UNKNOWN status for transactions
- Partial responses with missing critical fields
- Recurring gateway inquiry failures

**Resolution Behaviors Validated**:
- ✅ UNKNOWN payments never auto-complete without confirmation
- ✅ Customer never charged during UNKNOWN states
- ✅ Reconciliation process resolves ambiguous transactions
- ✅ Manual intervention workflow for unresolvable cases
- ✅ Audit trail preservation throughout resolution

**Key Test Methods**:
```java
@Test void gatewayTimeoutDuringPaymentProcessing_paymentEntersUnknownState()
@Test void reconciliationResolvesUnknownPaymentToCompleted_customerChargedCorrectly()
@Test void multipleReconciliationAttemptsForUnresolvableUnknown_eventuallyManualIntervention()
@Test void concurrentUnknownPaymentsWithSameMerchant_systemHandlesLoadGracefully()
```

**Success Criteria**: Zero false charges, complete audit trail, and bounded resolution time (10 minutes for automated, 24 hours for manual).

## Chaos Testing Patterns

### Controlled Failure Injection
```java
@SpyBean private PaymentGatewayService paymentGatewayService;

doThrow(new RuntimeException("Simulated gateway failure"))
    .doCallRealMethod() // Recovery after failure
    .when(paymentGatewayService).processPayment(any());
```

**Pattern**: Mock-based failure injection with recovery simulation.

### Time-Based Failure Windows
```java
@Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
void redisFailureDuringPeakLoad_systemMaintainsThroughput() {
    // Simulate failure for 10 seconds, then recovery
    Thread.sleep(6000); // Wait for lease timeout
    // Verify system recovered within timeout
}
```

**Pattern**: Time-bounded chaos with recovery validation.

### Concurrent Failure Scenarios
```java
CountDownLatch chaos = new CountDownLatch(1);
// Multiple threads experiencing failures simultaneously
chaos.countDown(); // Trigger simultaneous failures
```

**Pattern**: Multi-threaded chaos to simulate realistic failure cascades.

### Gradual Recovery Testing
```java
// Phase 1: Introduce failure
// Phase 2: Partial recovery (some nodes back online)  
// Phase 3: Full recovery
// Validate system behavior at each phase
```

**Pattern**: Staged recovery to test system behavior during healing.

## Infrastructure Chaos Targets

### Network Layer
- Connection timeouts
- Packet loss simulation  
- Network partitions
- DNS resolution failures

### Database Layer
- Connection pool exhaustion
- Query timeout simulation
- Deadlock injection
- Replica lag simulation

### Cache Layer (Redis)
- Cluster node failures
- Memory pressure simulation
- Network isolation
- Command timeout injection

### Application Layer
- Memory pressure (OOM simulation)
- CPU saturation
- Thread pool exhaustion
- Dependency timeout cascades

## Observability During Chaos

### Metrics Monitored
- System throughput degradation percentage
- Error rate increase patterns
- Recovery time measurements
- Resource utilization during failures

### Logging Requirements
```java
log.warn("Chaos Test: Redis connection failed - operation={}, degraded_mode=true", 
         operationId);
```

**Pattern**: Clear markers for chaos-induced vs real failures.

### Alerting Simulation
- Monitor alert firing during chaos tests
- Validate alert suppression during planned chaos
- Test escalation paths for prolonged failures

## Test Environment Requirements

### Resource Allocation
- **CPU**: 4+ cores (for concurrent chaos simulation)
- **Memory**: 8GB+ (for memory pressure testing)
- **Network**: Isolated test network for partition simulation
- **Storage**: Fast SSD for realistic I/O patterns

### Infrastructure Dependencies
- PostgreSQL with realistic connection limits
- Redis cluster with multiple nodes
- Mock payment gateway with configurable failures
- Load balancer with health check simulation

### Test Data Isolation
- Dedicated test database per chaos run
- Isolated Redis keyspace
- Separate outbox event queues
- Clean slate for each chaos scenario

## Failure Recovery Validation

### Recovery Time Objectives (RTO)
- **Cache failure recovery**: < 30 seconds
- **Database connection recovery**: < 60 seconds  
- **Event processing recovery**: < 120 seconds
- **Payment gateway recovery**: < 300 seconds

### Recovery Point Objectives (RPO)
- **Zero data loss**: All committed transactions preserved
- **Event ordering**: Outbox events processed in order
- **Idempotency integrity**: No duplicate operations post-recovery

### Health Check Validation
```java
@Test void systemHealthChecksDuringChaos_accuratelyReflectSystemState() {
    // Verify health endpoints show degraded state during failures
    // Verify health recovery after chaos resolution
}
```

## Integration with Production Monitoring

### Chaos Test Metrics Export
- Export chaos test results to production monitoring
- Compare chaos behavior with production incident patterns
- Use chaos results to calibrate production alerting thresholds

### Production Chaos Preparation
- Chaos tests validate production runbooks
- Test disaster recovery procedures
- Validate monitoring and alerting under failure conditions

## Maintenance and Evolution

### Adding New Chaos Scenarios
1. Identify production failure modes from incident reports
2. Create controlled test reproduction
3. Define success criteria for graceful degradation
4. Implement monitoring to detect regression

### Chaos Test Scheduling
- **Daily**: Quick chaos suite (5-minute scenarios)
- **Weekly**: Full chaos validation (all scenarios)
- **Pre-release**: Comprehensive chaos regression testing
- **Post-incident**: Chaos reproduction of actual failures

### Failure Pattern Analysis
- Track common failure modes across chaos tests
- Identify system weak points requiring hardening
- Correlate chaos test results with production incidents

## Related Documentation

- [Concurrency Test Strategy](01-concurrency-test-strategy.md) - Concurrent behavior under failures
- [How This Scales](../architecture/15-how-this-scales.md) - Production resilience architecture
- [Incident Response](../operations/04-incident-response.md) - Production chaos response procedures

---

**Phase 24 Status**: ✅ **COMPLETE** - All 4 chaos integration tests implemented with comprehensive failure simulation.

**Next Steps**: Integrate chaos tests into CI/CD pipeline with appropriate resource allocation and monitoring.