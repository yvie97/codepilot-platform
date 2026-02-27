package com.codepilot.orchestrator.skill.impl;

import com.codepilot.orchestrator.skill.*;
import org.springframework.stereotype.Component;

@Component
public class SearchCodeSkill implements Skill<Void, Void> {

    private static final SkillManifest MANIFEST = new SkillManifest(
            "search_code", "1.0.0",
            "search_code(pattern: str, path: str = \".\") -> list[dict]",
            "Search for a regex pattern using ripgrep. Returns [{file, line, text}, ...].",
            ExecutionTarget.PYTHON_EXECUTOR);

    private static final SkillPolicy POLICY = SkillPolicy.readOnly(15);

    @Override public SkillManifest manifest() { return MANIFEST; }
    @Override public SkillPolicy   policy()   { return POLICY; }

    @Override
    public Void execute(Void input, SkillExecutionContext ctx) {
        throw new UnsupportedOperationException("search_code is invoked via Python sandbox");
    }
}
