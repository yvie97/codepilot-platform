package com.codepilot.orchestrator.api;

import com.codepilot.orchestrator.api.dto.JobResponse;
import com.codepilot.orchestrator.api.dto.StepResponse;
import com.codepilot.orchestrator.api.dto.SubmitJobRequest;
import com.codepilot.orchestrator.model.AgentRole;
import com.codepilot.orchestrator.model.Job;
import com.codepilot.orchestrator.model.Step;
import com.codepilot.orchestrator.model.StepState;
import com.codepilot.orchestrator.service.JobService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for job lifecycle.
 *
 * POST /jobs               — submit a new repair job
 * GET  /jobs/{id}          — poll the current state of a job
 * GET  /jobs/{id}/steps    — list all pipeline steps with their results
 * GET  /jobs/{id}/report   — structured run summary produced by the FINALIZER agent
 */
@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobService   jobService;
    private final ObjectMapper objectMapper;

    public JobController(JobService jobService, ObjectMapper objectMapper) {
        this.jobService   = jobService;
        this.objectMapper = objectMapper;
    }

    /**
     * Submit a new repair job.
     *
     * Example:
     *   curl -X POST http://localhost:8080/jobs \
     *     -H "Content-Type: application/json" \
     *     -d '{"repoUrl":"https://github.com/apache/commons-lang.git","gitRef":"main"}'
     */
    @PostMapping
    public ResponseEntity<JobResponse> submit(@RequestBody SubmitJobRequest req) {
        Job job = jobService.submit(req.repoUrl(), req.gitRef());
        return ResponseEntity.status(HttpStatus.CREATED).body(JobResponse.from(job));
    }

    /**
     * Poll the current state of a job.
     * Returns 404 if the job ID is not found.
     */
    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable UUID id) {
        return jobService.findById(id)
                .map(JobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Job not found: " + id));
    }

    /**
     * List all pipeline steps for a job with their current state and results.
     *
     * Useful for:
     *   - Watching progress turn-by-turn during development
     *   - Extracting resultJson from each agent for benchmark evaluation
     *
     * Returns 404 if the job ID is not found.
     */
    @GetMapping("/{id}/steps")
    public List<StepResponse> getSteps(@PathVariable UUID id) {
        jobService.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));
        return jobService.getSteps(id).stream()
                .map(StepResponse::from)
                .toList();
    }

    /**
     * Return the structured run summary produced by the FINALIZER agent.
     *
     * This is the lightweight "timeline artifact" substitute: the FINALIZER
     * writes a JSON summary into its step's resultJson, and this endpoint
     * surfaces it directly without needing a separate artifact store.
     *
     * HTTP 200 — job is DONE and the FINALIZER result is available
     * HTTP 202 — job is still running (FINALIZER not yet complete)
     * HTTP 404 — job ID not found
     */
    @GetMapping("/{id}/report")
    public ResponseEntity<Map<String, Object>> getReport(@PathVariable UUID id) {
        Job job = jobService.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));

        // Find the completed FINALIZER step
        List<Step> steps = jobService.getSteps(id);
        Step finalizer = steps.stream()
                .filter(s -> s.getRole() == AgentRole.FINALIZER
                          && s.getState() == StepState.DONE
                          && s.getResultJson() != null)
                .findFirst()
                .orElse(null);

        if (finalizer == null) {
            // Job not yet done — return 202 Accepted with current state
            return ResponseEntity.accepted()
                    .body(Map.of("status", "pending", "jobState", job.getState().name()));
        }

        // Parse and return the FINALIZER's result JSON as the report
        try {
            Map<String, Object> report = objectMapper.readValue(
                    finalizer.getResultJson(),
                    new TypeReference<>() {});
            // Enrich with job-level metadata
            report.put("jobId",      job.getId().toString());
            report.put("jobState",   job.getState().name());
            report.put("createdAt",  job.getCreatedAt().toString());
            report.put("updatedAt",  job.getUpdatedAt().toString());
            report.put("iterations", job.getIterationCount());
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            // FINALIZER result is not valid JSON — return it as raw text
            return ResponseEntity.ok(Map.of(
                    "jobId",    job.getId().toString(),
                    "jobState", job.getState().name(),
                    "report",   finalizer.getResultJson()));
        }
    }
}
