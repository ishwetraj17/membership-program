package com.firstclub.integrity.entity;

/**
 * Lifecycle state of an {@link IntegrityCheckRun}.
 */
public enum IntegrityCheckRunStatus {
    /** Engine is currently executing checkers. */
    RUNNING,
    /** All checkers finished; none failed. */
    COMPLETED,
    /** All checkers finished; at least one reported FAIL or ERROR. */
    FAILED,
    /** The run itself encountered a fatal error before all checkers could finish. */
    ERROR
}
