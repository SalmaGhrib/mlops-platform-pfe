package com.mlops.platform.entity;

/**
 * Cause du déclenchement d'un réentraînement.
 */
public enum RetrainingTriggerType {
    SCHEDULED,
    MANUAL,
    GITHUB_PUSH,
    FAILED_RUNS,
    PREDICTION_ERRORS,
    DATA_DRIFT,
    PERFORMANCE_DROP,
    SMART_MONITORING,
    SKIPPED_NO_SIGNAL,
    SKIPPED_SUSPECT_DATA,
    BLOCKED_BY_SECURITY
}
