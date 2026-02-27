package com.codepilot.orchestrator.skill;

public class SkillNotFoundException extends RuntimeException {
    public SkillNotFoundException(String name) {
        super("No skill registered with name: '" + name + "'");
    }
}
