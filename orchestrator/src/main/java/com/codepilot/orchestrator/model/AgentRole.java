package com.codepilot.orchestrator.model;

/**
 * The six agent roles in the CodePilot pipeline (§5.3).
 *
 * Each role maps to one Step row and one Claude conversation.
 * The roles run sequentially; each feeds its result_json into
 * the next role's system prompt.
 */
public enum AgentRole {
    REPO_MAPPER,    // Reads the repo, builds a file map and summary
    PLANNER,        // Produces a step-by-step repair plan
    IMPLEMENTER,    // Writes and applies the code changes
    TESTER,         // Runs mvn test, interprets results
    REVIEWER,       // Final diff review and approval
    FINALIZER       // Produces a structured run summary (timeline, cost, stats)
}
