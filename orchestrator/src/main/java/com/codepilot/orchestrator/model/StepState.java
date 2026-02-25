package com.codepilot.orchestrator.model;

/**
 * Execution state of a single pipeline Step.
 *
 * Transitions:
 *   PENDING → RUNNING (claimed by a worker)
 *   RUNNING → DONE    (agent finished successfully)
 *   RUNNING → FAILED  (max retries exhausted or fatal error)
 *   FAILED  → PENDING (reset for retry, increments attempt counter)
 */
public enum StepState {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}
