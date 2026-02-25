package com.codepilot.orchestrator.executor.dto;

/**
 * Request body for POST /workspace/restore.
 * Must match models.RestoreRequest in executor/models.py.
 */
public record RestoreRequest(String workspace_ref, String snapshot_key) {}
