package com.codepilot.orchestrator.skill.impl;

import com.codepilot.orchestrator.skill.*;
import org.springframework.stereotype.Component;

@Component
public class WriteFileSkill implements Skill<Void, Void> {

    private static final SkillManifest MANIFEST = new SkillManifest(
            "write_file", "1.0.0",
            "write_file(path: str, content: str) -> None",
            "Write content to a file (creates parent dirs automatically).",
            ExecutionTarget.PYTHON_EXECUTOR);

    private static final SkillPolicy POLICY = SkillPolicy.writeAllowed(10);

    @Override public SkillManifest manifest() { return MANIFEST; }
    @Override public SkillPolicy   policy()   { return POLICY; }

    @Override
    public Void execute(Void input, SkillExecutionContext ctx) {
        throw new UnsupportedOperationException("write_file is invoked via Python sandbox");
    }
}
