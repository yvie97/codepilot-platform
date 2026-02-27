package com.codepilot.orchestrator.skill;

/**
 * Every tool capability available to agents is registered as a Skill (§6.1).
 *
 * A Skill is a versioned, policy-enforced, observable execution unit. This
 * decouples <em>what</em> a skill does from <em>how and where</em> it runs,
 * letting the platform enforce per-skill policies and collect per-skill
 * telemetry uniformly across all agent roles.
 *
 * <p>Execution routing (§6.2):
 * <ul>
 *   <li>{@link ExecutionTarget#JAVA_LOCAL} — runs directly in the
 *       orchestrator JVM; execute() must be a pure, side-effect-free
 *       computation (parsing, heuristics, policy checks).</li>
 *   <li>{@link ExecutionTarget#PYTHON_EXECUTOR} — the skill describes a
 *       Python function injected into the agent sandbox.  These skills are
 *       invoked by agents via emitted code blocks; their execute() is never
 *       called from Java and throws {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * @param <I> Input type (String for PYTHON_EXECUTOR; typed record for JAVA_LOCAL)
 * @param <O> Output type
 */
public interface Skill<I, O> {

    /** Identity, documentation, and routing metadata. */
    SkillManifest manifest();

    /** Execution constraints enforced before calling execute(). */
    SkillPolicy policy();

    /**
     * Execute the skill.
     *
     * For {@link ExecutionTarget#PYTHON_EXECUTOR} skills this method is not
     * invoked by Java code — agents call these tools via Python code blocks
     * executed in the sandbox.  Implementations should throw
     * {@link UnsupportedOperationException}.
     *
     * @throws SkillException on policy violation, timeout, or execution error
     */
    O execute(I input, SkillExecutionContext ctx) throws SkillException;
}
