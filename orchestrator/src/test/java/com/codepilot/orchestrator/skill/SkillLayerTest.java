package com.codepilot.orchestrator.skill;

import com.codepilot.orchestrator.skill.impl.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Skills Layer (§6).
 * No Spring context — all wiring is done manually.
 */
class SkillLayerTest {

    SkillRegistry registry;
    CheckPolicySkill checkPolicy;
    SkillExecutionContext ctx;

    @BeforeEach
    void setUp() {
        checkPolicy = new CheckPolicySkill();

        List<Skill<?, ?>> allSkills = List.of(
                new ReadFileSkill(),
                new WriteFileSkill(),
                new ListFilesSkill(),
                new SearchCodeSkill(),
                new ApplyPatchSkill(),
                new GitDiffSkill(),
                new RunCommandSkill(),
                checkPolicy
        );
        registry = new SkillRegistry(allSkills, new SimpleMeterRegistry());
        ctx = new SkillExecutionContext("ws-test", UUID.randomUUID());
    }

    // ------------------------------------------------------------------
    // SkillRegistry — registration and lookup
    // ------------------------------------------------------------------

    @Test
    void registry_registersAllSkills() {
        assertThat(registry.skillNames()).containsExactlyInAnyOrder(
                "read_file", "write_file", "list_files", "search_code",
                "apply_patch", "git_diff", "run_command", "check_policy");
    }

    @Test
    void registry_get_unknownSkill_throwsNotFoundException() {
        assertThatThrownBy(() -> registry.get("no_such_skill"))
                .isInstanceOf(SkillNotFoundException.class)
                .hasMessageContaining("no_such_skill");
    }

    // ------------------------------------------------------------------
    // SkillRegistry — tool documentation generation (§6.3)
    // ------------------------------------------------------------------

    @Test
    void buildToolDocumentation_containsAllPythonExecutorSkills() {
        String docs = registry.buildToolDocumentation();

        assertThat(docs).contains("read_file(path: str) -> str");
        assertThat(docs).contains("apply_patch(diff: str) -> dict");
        assertThat(docs).contains("run_command(cmd: list[str], timeout: int = 300) -> dict");
        assertThat(docs).contains("git_diff(base: str");
        assertThat(docs).contains("search_code(pattern: str");
    }

    @Test
    void buildToolDocumentation_containsRulesSection() {
        String docs = registry.buildToolDocumentation();
        assertThat(docs).contains("RULES:");
        assertThat(docs).contains("<result>...</result>");
    }

    // ------------------------------------------------------------------
    // CheckPolicySkill (JAVA_LOCAL) — actual execution
    // ------------------------------------------------------------------

    @Test
    void checkPolicy_cleanDiff_approved() {
        String diff = """
                diff --git a/Foo.java b/Foo.java
                +++ b/Foo.java
                +    return value < min ? min : Math.min(value, max);
                -    return value < min ? max : Math.min(value, min);
                """;

        var report = checkPolicy.execute(new CheckPolicySkill.Input(diff), ctx);

        assertThat(report.approved()).isTrue();
        assertThat(report.violations()).isEmpty();
        assertThat(report.linesAdded()).isGreaterThan(0);
    }

    @Test
    void checkPolicy_ignoreAnnotation_violationReported() {
        String diff = "+    @Ignore\n+    public void testSomething() {}\n";

        var report = checkPolicy.execute(new CheckPolicySkill.Input(diff), ctx);

        assertThat(report.approved()).isFalse();
        assertThat(report.violations()).anyMatch(v -> v.contains("Disabled test"));
    }

    @Test
    void checkPolicy_hardcodedSecret_violationReported() {
        String diff = "+    String apiKey = \"sk-abcdef1234567890\";\n";

        var report = checkPolicy.execute(new CheckPolicySkill.Input(diff), ctx);

        assertThat(report.approved()).isFalse();
        assertThat(report.violations()).anyMatch(v -> v.contains("secret"));
    }

    @Test
    void checkPolicy_oversizedPatch_violationReported() {
        // Generate a diff with more than 300 added lines
        String addedLines = "+line\n".repeat(301);

        var report = checkPolicy.execute(new CheckPolicySkill.Input(addedLines), ctx);

        assertThat(report.approved()).isFalse();
        assertThat(report.violations()).anyMatch(v -> v.contains("LOC"));
    }

    @Test
    void checkPolicy_nullDiff_notApproved() {
        var report = checkPolicy.execute(new CheckPolicySkill.Input(null), ctx);
        assertThat(report.approved()).isFalse();
    }

    // ------------------------------------------------------------------
    // PYTHON_EXECUTOR skills — execute() must throw
    // ------------------------------------------------------------------

    @Test
    void pythonExecutorSkills_execute_throwsUnsupportedOperation() {
        assertThatThrownBy(() ->
                new ApplyPatchSkill().execute(null, ctx))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------
    // SkillRegistry.execute() — metrics instrumentation
    // ------------------------------------------------------------------

    @Test
    void registry_execute_javaLocalSkill_incrementsCallCounter() {
        SimpleMeterRegistry meterReg = new SimpleMeterRegistry();
        registry = new SkillRegistry(List.of(checkPolicy), meterReg);

        registry.execute("check_policy",
                new CheckPolicySkill.Input("+return 42;\n"), ctx);

        assertThat(meterReg.counter("codepilot.skill.calls",
                "skill", "check_policy", "status", "success").count())
                .isEqualTo(1.0);
    }
}
