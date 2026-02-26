package com.codepilot.orchestrator.executor;

import com.codepilot.orchestrator.executor.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for the Python executor service.
 *
 * Wraps all five executor endpoints (§7.1) with typed Java methods.
 * Uses java.net.http.HttpClient (same approach as ClaudeClient) so we
 * have explicit control over every header and byte on the wire.
 *
 * The orchestrator calls this from the worker thread pool, so blocking
 * I/O here is acceptable.
 */
@Component
public class WorkspaceClient {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceClient.class);

    private final HttpClient   http;
    private final ObjectMapper json;
    private final String       baseUrl;

    public WorkspaceClient(
            @Value("${codepilot.executor.base-url}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.json    = objectMapper;
        this.http    = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // uvicorn doesn't support h2c upgrade
                .connectTimeout(Duration.ofSeconds(10))
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
        String body = toJson(Map.of("workspace_ref", workspaceRef,
                                    "repo_url",      repoUrl,
                                    "git_ref",       gitRef));
        post("/workspace/create", body, "createWorkspace for " + workspaceRef);
    }

    /**
     * Snapshot the workspace for potential rollback (§8.4).
     *
     * @return snapshot_key — store this and pass to restoreWorkspace() if a rollback is needed
     */
    public String snapshotWorkspace(String workspaceRef) {
        log.info("Snapshotting workspace '{}'", workspaceRef);
        String body = toJson(Map.of("workspace_ref", workspaceRef));
        String respBody = post("/workspace/snapshot", body, "snapshotWorkspace for " + workspaceRef);
        try {
            SnapshotResponse resp = json.readValue(respBody, SnapshotResponse.class);
            log.info("Snapshot '{}' created ({} bytes)", resp.snapshot_key(), resp.size_bytes());
            return resp.snapshot_key();
        } catch (JsonProcessingException e) {
            throw new ExecutorException("Failed to parse snapshotWorkspace response", e);
        }
    }

    /**
     * Roll a workspace back to a previously taken snapshot (§8.4).
     */
    public void restoreWorkspace(String workspaceRef, String snapshotKey) {
        log.info("Restoring workspace '{}' from snapshot '{}'", workspaceRef, snapshotKey);
        String body = toJson(Map.of("workspace_ref", workspaceRef,
                                    "snapshot_key",  snapshotKey));
        post("/workspace/restore", body, "restoreWorkspace for " + workspaceRef);
    }

    /**
     * Delete a workspace when the job is done (§4.1).
     */
    public void deleteWorkspace(String workspaceRef) {
        log.info("Deleting workspace '{}'", workspaceRef);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/workspace/" + workspaceRef))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new ExecutorException(
                        "deleteWorkspace failed for " + workspaceRef
                        + " — HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (ExecutorException e) {
            throw e;
        } catch (Exception e) {
            throw new ExecutorException("deleteWorkspace failed for " + workspaceRef, e);
        }
    }

    /**
     * Execute a Python code action in the sandbox (§7.1).
     *
     * @param code       Python code block extracted from Claude's response
     * @param timeoutSec Wall-clock deadline; executor kills the code after this
     * @return ExecutionResult containing stdout, stderr, exit_code
     */
    public ExecutionResult runCode(String workspaceRef, String code, int timeoutSec) {
        String body = toJson(Map.of("code",          code,
                                    "workspace_ref", workspaceRef,
                                    "timeout_sec",   timeoutSec));
        // Allow a bit more wall-clock time than the sandbox timeout.
        String respBody = post("/workspace/run_code", body,
                "runCode for workspace " + workspaceRef,
                Duration.ofSeconds(timeoutSec + 30));
        try {
            return json.readValue(respBody, ExecutionResult.class);
        } catch (JsonProcessingException e) {
            throw new ExecutorException("Failed to parse runCode response", e);
        }
    }

    /** Convenience overload with default 60-second timeout. */
    public ExecutionResult runCode(String workspaceRef, String code) {
        return runCode(workspaceRef, code, 60);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /** POST with default 120-second timeout; returns response body as String. */
    private String post(String path, String jsonBody, String opName) {
        return post(path, jsonBody, opName, Duration.ofSeconds(120));
    }

    /** POST with an explicit timeout; returns response body as String. */
    private String post(String path, String jsonBody, String opName, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept",       "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new ExecutorException(
                        opName + " failed — HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (ExecutorException e) {
            throw e;
        } catch (Exception e) {
            throw new ExecutorException(opName + " failed", e);
        }
    }

    /** Serialize obj to JSON string; throws ExecutorException on failure. */
    private String toJson(Object obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new ExecutorException("JSON serialization failed", e);
        }
    }
}
