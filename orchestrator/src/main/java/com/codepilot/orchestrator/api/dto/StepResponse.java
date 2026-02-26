package com.codepilot.orchestrator.api.dto;

import com.codepilot.orchestrator.model.AgentRole;
import com.codepilot.orchestrator.model.Step;
import com.codepilot.orchestrator.model.StepState;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of a pipeline step returned by GET /jobs/{id}/steps.
 *
 * resultJson contains the agent's structured output (e.g. repo map JSON,
 * repair plan JSON, test-pass/fail JSON) and is the primary artefact
 * used by the benchmark evaluator.
 */
public record StepResponse(
        UUID       id,
        AgentRole  role,
        StepState  state,
        int        attempt,
        String     workerId,
        Instant    createdAt,
        Instant    startedAt,
        Instant    finishedAt,
        Instant    heartbeatAt,
        String     resultJson
) {
    public static StepResponse from(Step s) {
        return new StepResponse(
                s.getId(),
                s.getRole(),
                s.getState(),
                s.getAttempt(),
                s.getWorkerId(),
                s.getCreatedAt(),
                s.getStartedAt(),
                s.getFinishedAt(),
                s.getHeartbeatAt(),
                s.getResultJson()
        );
    }
}
