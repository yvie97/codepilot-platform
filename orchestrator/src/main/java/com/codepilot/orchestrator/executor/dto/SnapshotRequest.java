package com.codepilot.orchestrator.executor.dto;

/**
 * Request body for POST /workspace/snapshot.
 * Must match models.SnapshotRequest in executor/models.py.
 */
public record SnapshotRequest(String workspace_ref) {}
