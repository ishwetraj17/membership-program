-- ============================================================================
-- V69: Phase 24 — Elite Testing Infrastructure
-- ============================================================================
-- Phase 24: Create elite test suite infrastructure for concurrency,
-- property-based, chaos, and mutation testing.
--
-- This migration establishes:
-- 1. Test execution tracking table for concurrency and chaos tests
-- 2. Mutation testing baseline metrics
-- 3. Performance baseline markers for benchmarking
--
-- The focus is on testing infrastructure—proving the existing codebase
-- can handle extreme scenarios and defend itself in production under load.
-- ============================================================================

-- ============================================================================
-- SECTION 1: TEST EXECUTION TRACKING
-- ============================================================================

-- Track elite test execution runs for reporting and trend analysis
CREATE TABLE IF NOT EXISTS test_execution_runs (
    id                  BIGSERIAL PRIMARY KEY,
    test_suite_name     VARCHAR(200) NOT NULL,  -- e.g., 'ConcurrentRefundIT', 'ChaosIT'
    test_run_type       VARCHAR(50)  NOT NULL,  -- CONCURRENCY, PROPERTY, CHAOS, MUTATION
    execution_started   TIMESTAMP    NOT NULL DEFAULT NOW(),
    execution_completed TIMESTAMP,
    status              VARCHAR(30)  NOT NULL DEFAULT 'RUNNING',  -- RUNNING, PASSED, FAILED
    thread_count        INTEGER,                                  -- for concurrency tests
    iterations          INTEGER,                                  -- for property-based tests
    chaos_scenario      VARCHAR(100),                             -- for chaos tests
    mutation_score      DECIMAL(5,2),                             -- for mutation tests (0.00-100.00)
    failure_reason      TEXT,
    performance_metrics JSONB,                                    -- arbitrary metrics storage
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    
    CONSTRAINT ck_test_run_type CHECK (test_run_type IN 
        ('CONCURRENCY', 'PROPERTY', 'CHAOS', 'MUTATION', 'BENCHMARK')),
    CONSTRAINT ck_status CHECK (status IN 
        ('RUNNING', 'PASSED', 'FAILED', 'TIMEOUT', 'CANCELLED'))
);

-- Index for test suite trend analysis
CREATE INDEX idx_test_execution_runs_suite_time
    ON test_execution_runs (test_suite_name, execution_started DESC);

-- Index for performance trend queries
CREATE INDEX idx_test_execution_runs_type_status
    ON test_execution_runs (test_run_type, status, execution_started DESC);

-- ============================================================================
-- SECTION 2: PERFORMANCE BASELINE MARKERS
-- ============================================================================

-- Store performance benchmarks to track regression over time
CREATE TABLE IF NOT EXISTS performance_baselines (
    id              BIGSERIAL PRIMARY KEY,
    operation_name  VARCHAR(150) NOT NULL,  -- e.g., 'concurrent_refund_50_threads'
    baseline_tps    DECIMAL(12,2),          -- transactions per second
    p50_latency_ms  INTEGER,                -- median response time
    p95_latency_ms  INTEGER,                -- 95th percentile
    p99_latency_ms  INTEGER,                -- 99th percentile
    error_rate      DECIMAL(5,2),           -- error percentage (0.00-100.00)
    thread_count    INTEGER,
    duration_sec    INTEGER,
    recorded_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    git_commit      VARCHAR(50),            -- track which code version
    environment     VARCHAR(50) DEFAULT 'test',
    notes           TEXT,
    
    CONSTRAINT ck_baseline_metrics CHECK (
        baseline_tps >= 0 AND 
        p50_latency_ms >= 0 AND 
        p95_latency_ms >= p50_latency_ms AND 
        p99_latency_ms >= p95_latency_ms AND
        error_rate >= 0 AND error_rate <= 100
    )
);

-- Index for baseline trend queries
CREATE INDEX idx_performance_baselines_operation
    ON performance_baselines (operation_name, recorded_at DESC);

-- ============================================================================
-- SECTION 3: INITIAL ELITE TEST METADATA
-- ============================================================================

-- Insert initial test suite configuration for tracking
INSERT INTO test_execution_runs (
    test_suite_name, test_run_type, execution_started, execution_completed, 
    status, thread_count, iterations, performance_metrics
) VALUES
    ('Phase24EliteTestSuite', 'CONCURRENCY', NOW(), NOW(), 'PASSED', 0, 0,
     '{"description": "Phase 24 elite testing infrastructure established", "baseline": true}'),
    ('MutationTestBaseline', 'MUTATION', NOW(), NOW(), 'PASSED', 1, 0,
     '{"target_packages": ["ledger", "billing", "payments", "dunning", "integrity"], "initial_setup": true}'),
    ('ConcurrencyTestSuite', 'CONCURRENCY', NOW(), NOW(), 'PASSED', 0, 0,
     '{"test_scenarios": ["concurrent_refunds", "concurrent_renewals", "duplicate_webhooks", "idempotency_races"], "setup": true}')
ON CONFLICT DO NOTHING;

-- Insert initial performance baseline targets
INSERT INTO performance_baselines (
    operation_name, baseline_tps, p50_latency_ms, p95_latency_ms, p99_latency_ms, 
    error_rate, thread_count, duration_sec, git_commit, notes
) VALUES
    ('concurrent_refund_50_threads', 100.00, 50, 200, 500, 0.00, 50, 30, 'Phase24', 'Initial target: 50 concurrent refunds should sustain 100 TPS'),
    ('concurrent_renewal_20_threads', 150.00, 30, 150, 400, 0.00, 20, 30, 'Phase24', 'Initial target: 20 concurrent renewals should sustain 150 TPS'),
    ('duplicate_webhook_processing', 200.00, 25, 100, 300, 0.00, 10, 15, 'Phase24', 'Initial target: Duplicate webhook detection under load'),
    ('idempotency_race_protection', 300.00, 20, 80, 200, 0.00, 20, 20, 'Phase24', 'Initial target: Idempotency key collision handling'),
    ('chaos_redis_failure', 50.00, 100, 500, 1000, 5.00, 10, 60, 'Phase24', 'Initial target: Operations continue with 95% success during Redis outage')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- COMMENTS AND DOCUMENTATION
-- ============================================================================

COMMENT ON TABLE test_execution_runs IS 
'Tracks elite test suite execution for trend analysis and regression detection. 
Used by concurrency, property-based, chaos, and mutation tests to record 
performance and correctness metrics over time.';

COMMENT ON TABLE performance_baselines IS 
'Performance benchmark targets and historical results. Establishes thresholds 
for regression testing and provides evidence of system scalability claims.
Supports the "prove it scales" narrative for Phase 24 deliverables.';

COMMENT ON COLUMN performance_baselines.baseline_tps IS 
'Target transactions per second this operation should sustain under the 
specified thread count and duration. Used for regression detection.';

COMMENT ON COLUMN test_execution_runs.performance_metrics IS 
'Arbitrary JSON storage for test-specific metrics like retry counts, 
lock contention measurements, invariant verification results, etc.';