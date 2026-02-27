package com.codepilot.orchestrator.skill;

/**
 * Where a skill's execute() call actually runs (§6.2).
 *
 * JAVA_LOCAL      — runs inside the orchestrator JVM; zero sandbox overhead.
 *                   Used for pure computation: policy checks, diff scoring,
 *                   Surefire XML parsing.
 *
 * PYTHON_EXECUTOR — forwarded to the executor service via HTTP; runs inside
 *                   the sandboxed Python process with filesystem and subprocess
 *                   access to the workspace.  Used for file I/O, git operations,
 *                   and build-tool invocations.
 */
public enum ExecutionTarget {
    JAVA_LOCAL,
    PYTHON_EXECUTOR
}
