package com.codepilot.orchestrator.api.dto;

/**
 * Request body for POST /jobs.
 * The caller supplies a git repo URL and the ref (branch/tag/commit) to repair.
 */
public record SubmitJobRequest(String repoUrl, String gitRef) {

    // Compact constructor: default gitRef to "main" if caller omits it.
    public SubmitJobRequest {
        if (gitRef == null || gitRef.isBlank()) gitRef = "main";
    }
}
