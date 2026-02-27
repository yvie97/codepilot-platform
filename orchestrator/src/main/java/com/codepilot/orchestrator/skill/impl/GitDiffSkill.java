package com.codepilot.orchestrator.skill.impl;

import com.codepilot.orchestrator.skill.*;
import org.springframework.stereotype.Component;

@Component
public class GitDiffSkill implements Skill<Void, Void> {

    private static final SkillManifest MANIFEST = new SkillManifest(
            "git_diff", "1.0.0",
            "git_diff(base: str = \"HEAD\") -> str",
            "Show the unified diff vs base commit.",
            ExecutionTarget.PYTHON_EXECUTOR);

    private static final SkillPolicy POLICY = SkillPolicy.readOnly(10);

    @Override public SkillManifest manifest() { return MANIFEST; }
    @Override public SkillPolicy   policy()   { return POLICY; }

    @Override
    public Void execute(Void input, SkillExecutionContext ctx) {
        throw new UnsupportedOperationException("git_diff is invoked via Python sandbox");
    }
}
