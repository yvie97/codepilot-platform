package com.codepilot.orchestrator.api.dto;

import com.codepilot.orchestrator.model.Job;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for POST /jobs and GET /jobs/{id}.
 * Contains enough information for the caller to poll job progress.
 */
public record JobResponse(
        UUID    id,
        String  state,
        String  repoUrl,
        String  gitRef,
        Instant createdAt,
        Instant updatedAt
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getState().name(),
                job.getRepoUrl(),
                job.getGitRef(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
