package com.codepilot.orchestrator.executor;

import com.codepilot.orchestrator.executor.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the Python executor service.
 *
 * Wraps all five executor endpoints (§7.1) with typed Java methods.
 * Uses Spring's RestClient (Spring 6.1 / Boot 3.4) — synchronous,
 * fluent, no extra dependencies needed.
 *
 * The orchestrator calls this from the worker thread pool, so blocking
 * I/O here is acceptable.
 */
@Component
public class WorkspaceClient {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceClient.class);

    private final RestClient http;

    public WorkspaceClient(
            @Value("${codepilot.executor.base-url}") String baseUrl) {
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept",       MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ------------------------------------------------------------------
    // Workspace lifecycle
    // ------------------------------------------------------------------

    /**
     * Clone a git repository into a new workspace (§4.1).
     *
     * @throws ExecutorException if the executor returns a non-2xx status
     */
    public void createWorkspace(String workspaceRef, String repoUrl, String gitRef) {
        log.info("Creating workspace '{}' from {} @ {}", workspaceRef, repoUrl, gitRef);
        try {
            http.post()
                    .uri("/workspace/create")
                    .body(new CreateWorkspaceRequest(workspaceRef, repoUrl, gitRef))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ExecutorException("createWorkspace failed for " + workspaceRef, e);
        }
    }

    /**
     * Snapshot the workspace for potential rollback (§8.4).
     *
     * @return snapshot_key — store this and pass to restoreWorkspace() if a rollback is needed
     */
    public String snapshotWorkspace(String workspaceRef) {
        log.info("Snapshotting workspace '{}'", workspaceRef);
        try {
            SnapshotResponse resp = http.post()
                    .uri("/workspace/snapshot")
                    .body(new SnapshotRequest(workspaceRef))
                    .retrieve()
                    .body(SnapshotResponse.class);
            log.info("Snapshot '{}' created ({} bytes)", resp.snapshot_key(), resp.size_bytes());
            return resp.snapshot_key();
        } catch (RestClientException e) {
            throw new ExecutorException("snapshotWorkspace failed for " + workspaceRef, e);
        }
    }

    /**
     * Roll a workspace back to a previously taken snapshot (§8.4).
     */
    public void restoreWorkspace(String workspaceRef, String snapshotKey) {
        log.info("Restoring workspace '{}' from snapshot '{}'", workspaceRef, snapshotKey);
        try {
            http.post()
                    .uri("/workspace/restore")
                    .body(new RestoreRequest(workspaceRef, snapshotKey))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ExecutorException("restoreWorkspace failed for " + workspaceRef, e);
        }
    }

    /**
     * Delete a workspace when the job is done (§4.1).
     */
    public void deleteWorkspace(String workspaceRef) {
        log.info("Deleting workspace '{}'", workspaceRef);
        try {
            http.delete()
                    .uri("/workspace/{ref}", workspaceRef)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ExecutorException("deleteWorkspace failed for " + workspaceRef, e);
        }
    }

    /**
     * Execute a Python code action in the sandbox (§7.1).
     *
     * Called on every turn of the agent loop. The agent reads the returned
     * observation string and decides its next action.
     *
     * @param code       Python code block extracted from Claude's response
     * @param timeoutSec Wall-clock deadline; executor kills the code after this
     * @return ExecutionResult containing stdout, stderr, exit_code
     */
    public ExecutionResult runCode(String workspaceRef, String code, int timeoutSec) {
        try {
            return http.post()
                    .uri("/workspace/run_code")
                    .body(new RunCodeRequest(code, workspaceRef, timeoutSec))
                    .retrieve()
                    .body(ExecutionResult.class);
        } catch (RestClientException e) {
            throw new ExecutorException("runCode failed for workspace " + workspaceRef, e);
        }
    }

    /** Convenience overload with default 60-second timeout. */
    public ExecutionResult runCode(String workspaceRef, String code) {
        return runCode(workspaceRef, code, 60);
    }
}
