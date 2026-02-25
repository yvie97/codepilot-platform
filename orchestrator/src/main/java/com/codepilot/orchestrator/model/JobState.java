package com.codepilot.orchestrator.model;

/**
 * States of the overall repair pipeline for a Job (§4.2).
 *
 * Transitions (happy path):
 *   INIT → MAP_REPO → PLAN → IMPLEMENT → TEST → REVIEW → FINALIZE → DONE
 *
 * Any state can transition to FAILED on a permanent error
 * (e.g. max retries exhausted, repo clone failed).
 */
public enum JobState {
    INIT,
    MAP_REPO,
    PLAN,
    IMPLEMENT,
    TEST,
    REVIEW,
    FINALIZE,
    DONE,
    FAILED
}
