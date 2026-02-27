package com.codepilot.orchestrator.skill;

/**
 * Execution constraints enforced by the registry before calling execute() (§6.1).
 *
 * @param networkAllowed    false for all executor-routed skills (executor pods have
 *                          no egress by design — see NetworkPolicy §9.2).
 * @param filesystemWrite   true only for patch/write skills that mutate the workspace.
 * @param commandTimeoutSec Wall-clock limit for the underlying operation.
 */
public record SkillPolicy(
        boolean networkAllowed,
        boolean filesystemWrite,
        int     commandTimeoutSec) {

    /** Convenience factory for read-only PYTHON_EXECUTOR skills. */
    public static SkillPolicy readOnly(int timeoutSec) {
        return new SkillPolicy(false, false, timeoutSec);
    }

    /** Convenience factory for workspace-mutating PYTHON_EXECUTOR skills. */
    public static SkillPolicy writeAllowed(int timeoutSec) {
        return new SkillPolicy(false, true, timeoutSec);
    }

    /** Convenience factory for JAVA_LOCAL skills (no subprocess, no filesystem). */
    public static SkillPolicy javaLocal() {
        return new SkillPolicy(false, false, 5);
    }
}
