package com.codepilot.orchestrator.executor.dto;

/**
 * Request body for POST /workspace/create on the Python executor.
 * Must match models.CreateWorkspaceRequest in executor/models.py.
 */
public record CreateWorkspaceRequest(
        String workspace_ref,
        String repo_url,
        String git_ref
) {}
