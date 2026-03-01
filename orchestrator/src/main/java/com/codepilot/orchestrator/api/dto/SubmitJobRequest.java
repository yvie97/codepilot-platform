package com.codepilot.orchestrator.api.dto;

/**
 * Request body for POST /jobs.
 *
 * Required: repoUrl, gitRef
 * Optional: taskDescription, failingTest — when provided they are forwarded to
 *   REPO_MAPPER and PLANNER so the agents know exactly what bug to look for.
 *   Ad-hoc submissions without these fields still work (fields default to null).
 */
public record SubmitJobRequest(String repoUrl, String gitRef,
                               String taskDescription, String failingTest) {

    // Compact constructor: default gitRef to "main" if caller omits it.
    public SubmitJobRequest {
        if (gitRef == null || gitRef.isBlank()) gitRef = "main";
    }
}
