package com.codepilot.orchestrator.skill.impl;

import com.codepilot.orchestrator.skill.*;
import org.springframework.stereotype.Component;

@Component
public class RunCommandSkill implements Skill<Void, Void> {

    private static final SkillManifest MANIFEST = new SkillManifest(
            "run_command", "1.0.0",
            "run_command(cmd: list[str], timeout: int = 300) -> dict",
            "Run an allowlisted command (mvn, java, git, rg). Returns {exit_code, stdout, stderr}.",
            ExecutionTarget.PYTHON_EXECUTOR);

    // writeAllowed because build commands may produce target/ files
    private static final SkillPolicy POLICY = SkillPolicy.writeAllowed(300);

    @Override public SkillManifest manifest() { return MANIFEST; }
    @Override public SkillPolicy   policy()   { return POLICY; }

    @Override
    public Void execute(Void input, SkillExecutionContext ctx) {
        throw new UnsupportedOperationException("run_command is invoked via Python sandbox");
    }
}
