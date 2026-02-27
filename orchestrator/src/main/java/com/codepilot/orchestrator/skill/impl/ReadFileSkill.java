package com.codepilot.orchestrator.skill.impl;

import com.codepilot.orchestrator.skill.*;
import org.springframework.stereotype.Component;

/**
 * Descriptor for the read_file() tool function available in the Python sandbox.
 * Agents call this directly via emitted code; execute() is not invoked from Java.
 */
@Component
public class ReadFileSkill implements Skill<Void, Void> {

    private static final SkillManifest MANIFEST = new SkillManifest(
            "read_file", "1.0.0",
            "read_file(path: str) -> str",
            "Read a file relative to the workspace root.",
            ExecutionTarget.PYTHON_EXECUTOR);

    private static final SkillPolicy POLICY = SkillPolicy.readOnly(10);

    @Override public SkillManifest manifest() { return MANIFEST; }
    @Override public SkillPolicy   policy()   { return POLICY; }

    @Override
    public Void execute(Void input, SkillExecutionContext ctx) {
        throw new UnsupportedOperationException(
                "read_file is invoked by agents via Python sandbox, not from Java");
    }
}
