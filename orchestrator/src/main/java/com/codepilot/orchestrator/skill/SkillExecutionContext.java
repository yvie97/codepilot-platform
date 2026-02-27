package com.codepilot.orchestrator.skill;

import java.util.UUID;

/**
 * Runtime context passed to every skill invocation (§6.1).
 *
 * Skills use this to locate the correct workspace on the executor
 * and to tag any metrics or logs with the owning job.
 */
public record SkillExecutionContext(String workspaceRef, UUID jobId) {}
