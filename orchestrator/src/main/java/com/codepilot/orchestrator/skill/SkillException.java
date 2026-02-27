package com.codepilot.orchestrator.skill;

/**
 * Thrown when a skill execution fails in a way that the orchestrator
 * should handle (e.g. policy violation, executor timeout, parse error).
 *
 * Unchecked so callers only catch it when they have a specific recovery
 * strategy — all other failures propagate as-is to failStep().
 */
public class SkillException extends RuntimeException {

    public enum Kind { POLICY_VIOLATION, EXECUTOR_ERROR, TIMEOUT, PARSE_ERROR }

    private final Kind kind;

    public SkillException(Kind kind, String message) {
        super("[" + kind + "] " + message);
        this.kind = kind;
    }

    public SkillException(Kind kind, String message, Throwable cause) {
        super("[" + kind + "] " + message, cause);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
