package com.codepilot.orchestrator.skill.impl;

import com.codepilot.orchestrator.skill.*;
import org.springframework.stereotype.Component;

@Component
public class ApplyPatchSkill implements Skill<Void, Void> {

    private static final SkillManifest MANIFEST = new SkillManifest(
            "apply_patch", "1.0.0",
            "apply_patch(diff: str) -> dict",
            "Apply a unified diff to the workspace using git apply. Returns {exit_code, stdout, stderr, success}.",
            ExecutionTarget.PYTHON_EXECUTOR);

    private static final SkillPolicy POLICY = SkillPolicy.writeAllowed(30);

    @Override public SkillManifest manifest() { return MANIFEST; }
    @Override public SkillPolicy   policy()   { return POLICY; }

    @Override
    public Void execute(Void input, SkillExecutionContext ctx) {
        throw new UnsupportedOperationException("apply_patch is invoked via Python sandbox");
    }
}
