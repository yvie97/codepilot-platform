package com.codepilot.orchestrator.skill.impl;

import com.codepilot.orchestrator.skill.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JAVA_LOCAL skill that checks a unified diff for policy violations (§6.2).
 *
 * Runs entirely in the orchestrator JVM — no subprocess, no sandbox required.
 * Called by the orchestrator after the REVIEWER step to enforce hard rules
 * that Claude might miss or be convinced to waive.
 *
 * Checks:
 *   - No {@code @Ignore} or {@code @Disabled} annotations added (disabled tests)
 *   - No lines matching common secret patterns (API keys, passwords in code)
 *   - Patch size within the configured LOC limit
 */
@Component
public class CheckPolicySkill implements Skill<CheckPolicySkill.Input, CheckPolicySkill.Report> {

    /** Maximum lines changed before the patch is considered too large. */
    private static final int MAX_PATCH_LOC = 300;

    // Patterns applied to added lines (lines starting with '+' in the diff)
    private static final Pattern IGNORE_ANNOTATION = Pattern.compile(
            "^\\+.*@(Ignore|Disabled)\\b");
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "^\\+.*(password|api.?key|secret|token)\\s*=\\s*[\"'][^\"']{4,}[\"']",
            Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------
    // Input / Output types
    // ------------------------------------------------------------------

    /**
     * @param diff The full unified diff produced by git_diff("HEAD").
     */
    public record Input(String diff) {}

    public record Report(
            boolean approved,
            List<String> violations,
            int linesAdded,
            int linesRemoved) {

        public boolean hasViolations() { return !violations.isEmpty(); }
    }

    // ------------------------------------------------------------------
    // Skill metadata
    // ------------------------------------------------------------------

    private static final SkillManifest MANIFEST = new SkillManifest(
            "check_policy", "1.0.0",
            "check_policy(diff: str) -> PolicyReport",
            "Check a unified diff for policy violations: disabled tests, secrets, oversized patches.",
            ExecutionTarget.JAVA_LOCAL);

    private static final SkillPolicy POLICY = SkillPolicy.javaLocal();

    @Override public SkillManifest manifest() { return MANIFEST; }
    @Override public SkillPolicy   policy()   { return POLICY; }

    // ------------------------------------------------------------------
    // Execution (runs in the orchestrator JVM)
    // ------------------------------------------------------------------

    @Override
    public Report execute(Input input, SkillExecutionContext ctx) throws SkillException {
        if (input == null || input.diff() == null || input.diff().isBlank()) {
            return new Report(false, List.of("Empty or null diff"), 0, 0);
        }

        List<String> violations = new ArrayList<>();
        int added   = 0;
        int removed = 0;

        for (String line : input.diff().lines().toList()) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
                if (IGNORE_ANNOTATION.matcher(line).find()) {
                    violations.add("Disabled test annotation found: " + line.strip());
                }
                if (SECRET_PATTERN.matcher(line).find()) {
                    violations.add("Potential secret in added code: " + line.strip());
                }
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removed++;
            }
        }

        int totalLoc = added + removed;
        if (totalLoc > MAX_PATCH_LOC) {
            violations.add("Patch is %d LOC (limit: %d)".formatted(totalLoc, MAX_PATCH_LOC));
        }

        return new Report(violations.isEmpty(), violations, added, removed);
    }
}
