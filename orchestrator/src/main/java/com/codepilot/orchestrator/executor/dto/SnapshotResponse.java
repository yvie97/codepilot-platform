package com.codepilot.orchestrator.executor.dto;

/**
 * Response from POST /workspace/snapshot.
 * Must match models.SnapshotResponse in executor/models.py.
 */
public record SnapshotResponse(
        String workspace_ref,
        String snapshot_key,
        long size_bytes
) {}
