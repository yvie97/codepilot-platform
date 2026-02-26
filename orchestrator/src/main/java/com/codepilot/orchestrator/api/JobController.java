package com.codepilot.orchestrator.api;

import com.codepilot.orchestrator.api.dto.JobResponse;
import com.codepilot.orchestrator.api.dto.SubmitJobRequest;
import com.codepilot.orchestrator.model.Job;
import com.codepilot.orchestrator.service.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * REST API for job lifecycle.
 *
 * POST /jobs          — submit a new repair job
 * GET  /jobs/{id}     — poll the current state of a job
 */
@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
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
}
