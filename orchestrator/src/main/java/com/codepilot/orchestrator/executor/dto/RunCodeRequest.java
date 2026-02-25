package com.codepilot.orchestrator.executor.dto;

/**
 * Request body for POST /workspace/run_code on the Python executor.
 * Must match models.RunCodeRequest in executor/models.py.
 */
public record RunCodeRequest(
        String code,
        String workspace_ref,
        int timeout_sec
) {
    public RunCodeRequest(String code, String workspaceRef) {
        this(code, workspaceRef, 60);
    }
}
