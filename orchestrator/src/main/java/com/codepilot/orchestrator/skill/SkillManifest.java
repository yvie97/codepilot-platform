package com.codepilot.orchestrator.skill;

/**
 * Identity and documentation contract for a skill (§6.1).
 *
 * @param name        Unique identifier used to look up the skill in the registry
 *                    and to reference it in agent system prompts (e.g. "apply_patch").
 * @param version     Semantic version; lets the registry detect incompatible changes.
 * @param signature   Full Python-style signature shown to the agent in its system prompt,
 *                    e.g. "apply_patch(diff: str) -> dict".
 * @param description One-sentence docstring injected verbatim into the agent's tool
 *                    documentation section.
 * @param target      Where this skill executes (JAVA_LOCAL vs PYTHON_EXECUTOR).
 */
public record SkillManifest(
        String          name,
        String          version,
        String          signature,
        String          description,
        ExecutionTarget target) {}
