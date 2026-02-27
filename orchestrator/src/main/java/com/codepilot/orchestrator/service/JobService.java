package com.codepilot.orchestrator.service;

import com.codepilot.orchestrator.executor.ExecutorException;
import com.codepilot.orchestrator.executor.WorkspaceClient;
import com.codepilot.orchestrator.model.*;
import com.codepilot.orchestrator.repository.JobRepository;
import com.codepilot.orchestrator.repository.StepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core business logic for job and step lifecycle.
 *
 * All public methods that touch the DB are @Transactional so that
 * SELECT FOR UPDATE SKIP LOCKED and the following UPDATE are atomic.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    // Ordered list of agent roles — defines the pipeline sequence.
    private static final List<AgentRole> PIPELINE = List.of(
            AgentRole.REPO_MAPPER,
            AgentRole.PLANNER,
            AgentRole.IMPLEMENTER,
            AgentRole.TESTER,
            AgentRole.REVIEWER
    );

    private final JobRepository     jobRepo;
    private final StepRepository    stepRepo;
    private final WorkspaceClient   workspaceClient;

    public JobService(JobRepository jobRepo,
                      StepRepository stepRepo,
                      WorkspaceClient workspaceClient) {
        this.jobRepo         = jobRepo;
        this.stepRepo        = stepRepo;
        this.workspaceClient = workspaceClient;
    }

    // ------------------------------------------------------------------
    // Job submission
    // ------------------------------------------------------------------

    /**
     * Create a new repair job and kick off the pipeline.
     *
     * Steps:
     *  1. Save a Job row (state = INIT)
     *  2. Use the job's UUID as the workspace_ref (keeps things simple)
     *  3. Create the workspace on the executor (git clone)
     *  4. Create the first Step: REPO_MAPPER (state = PENDING)
     *  5. Update job state to MAP_REPO — the scheduler will pick it up
     */
    @Transactional
    public Job submit(String repoUrl, String gitRef) {
        // Step 1-2: persist the job and set workspace_ref = job UUID
        Job job = jobRepo.save(new Job(repoUrl, gitRef));
        job.setWorkspaceRef(job.getId().toString());

        // Step 3: clone the repo into a workspace (may take 30-60 s for large repos)
        try {
            workspaceClient.createWorkspace(job.getWorkspaceRef(), repoUrl, gitRef);
        } catch (ExecutorException e) {
            log.error("Workspace creation failed for job {}", job.getId(), e);
            job.setState(JobState.FAILED);
            return jobRepo.save(job);
        }

        // Step 4-5: enqueue the first pipeline step and advance job state
        stepRepo.save(new Step(job, AgentRole.REPO_MAPPER));
        job.setState(JobState.MAP_REPO);
        return jobRepo.save(job);
    }

    public Optional<Job> findById(UUID id) {
        return jobRepo.findById(id);
    }

    // ------------------------------------------------------------------
    // Step claiming (called by the scheduler in a background thread)
    // ------------------------------------------------------------------

    /**
     * Claim the next PENDING step for execution.
     *
     * Uses SELECT FOR UPDATE SKIP LOCKED so multiple worker threads can
     * call this concurrently without claiming the same step twice.
     *
     * The whole method is @Transactional: the lock is held from the SELECT
     * until the UPDATE (state = RUNNING) commits — then released.
     */
    @Transactional
    public Optional<Step> claimNextStep(String workerId) {
        Optional<Step> opt = stepRepo.claimNextPendingStep();
        opt.ifPresent(step -> {
            step.setState(StepState.RUNNING);
            step.setWorkerId(workerId);
            step.setStartedAt(Instant.now());
            step.setHeartbeatAt(Instant.now());
            stepRepo.save(step);
            log.info("Worker '{}' claimed step {} (job={}, role={})",
                    workerId, step.getId(), step.getJob().getId(), step.getRole());
        });
        return opt;
    }

    // ------------------------------------------------------------------
    // Step completion (called by AgentLoop when agent finishes)
    // ------------------------------------------------------------------

    /**
     * Mark a step as DONE and advance the pipeline.
     *
     * For TESTER steps that report tests_passed=false, this method implements
     * the backtracking logic (§4.2):
     *   - First failure:  re-queue a new PLANNER step and return to PLAN state.
     *   - Second failure: backtrack budget exhausted, mark job FAILED.
     * The consecutive_test_failures counter resets whenever TESTER passes.
     *
     * For all other roles, the pipeline advances normally.
     */
    @Transactional
    public void completeStep(Step step, String resultJson) {
        step.setState(StepState.DONE);
        step.setFinishedAt(Instant.now());
        step.setResultJson(resultJson);
        stepRepo.save(step);

        Job job = jobRepo.findById(step.getJob().getId()).orElseThrow();

        // ── Backtracking (§4.2) ──────────────────────────────────────────────
        if (step.getRole() == AgentRole.TESTER && !parseTestsPassed(resultJson)) {
            job.incrementConsecutiveTestFailures();
            if (job.getConsecutiveTestFailures() >= 2) {
                // Two consecutive test failures → give up.
                job.setState(JobState.FAILED);
                jobRepo.save(job);
                log.error("Job {} FAILED: {} consecutive TESTER failures, backtrack budget exhausted.",
                        job.getId(), job.getConsecutiveTestFailures());
                cleanupWorkspace(job);
            } else {
                // First failure → backtrack: re-queue PLANNER with failure context in priorResults.
                job.setIterationCount(job.getIterationCount() + 1);
                job.setState(JobState.PLAN);
                jobRepo.save(job);
                stepRepo.save(new Step(job, AgentRole.PLANNER));
                log.warn("Job {} backtracking to PLAN (iteration={}, consecutiveTestFailures={})",
                        job.getId(), job.getIterationCount(), job.getConsecutiveTestFailures());
            }
            return;
        }

        // TESTER passed — reset the failure streak.
        if (step.getRole() == AgentRole.TESTER) {
            job.setConsecutiveTestFailures(0);
        }

        // ── Normal pipeline advance ──────────────────────────────────────────
        AgentRole nextRole = nextRole(step.getRole());

        if (nextRole == null) {
            // All roles finished — job is complete.
            job.setState(JobState.DONE);
            jobRepo.save(job);
            log.info("Job {} DONE", job.getId());
            cleanupWorkspace(job);
        } else {
            // Enqueue the next agent role.
            stepRepo.save(new Step(job, nextRole));
            job.setState(jobStateFor(nextRole));
            jobRepo.save(job);
            log.info("Job {} advancing to {}", job.getId(), job.getState());
        }
    }

    /**
     * Mark a step as FAILED.
     *
     * If the step has been attempted fewer than MAX_ATTEMPTS times,
     * reset it to PENDING for a retry. Otherwise mark the whole job FAILED.
     */
    @Transactional
    public void failStep(Step step, String reason) {
        final int MAX_ATTEMPTS = 3;
        step.incrementAttempt();
        step.setFinishedAt(Instant.now());
        step.setWorkerId(null);

        if (step.getAttempt() < MAX_ATTEMPTS) {
            step.setState(StepState.PENDING);   // will be claimed again by scheduler
            step.setStartedAt(null);
            step.setFinishedAt(null);
            log.warn("Step {} failed (attempt {}/{}), will retry. Reason: {}",
                    step.getId(), step.getAttempt(), MAX_ATTEMPTS, reason);
        } else {
            step.setState(StepState.FAILED);
            stepRepo.save(step);

            Job job = jobRepo.findById(step.getJob().getId()).orElseThrow();
            job.setState(JobState.FAILED);
            jobRepo.save(job);
            log.error("Step {} permanently failed after {} attempts. Job {} → FAILED.",
                    step.getId(), MAX_ATTEMPTS, job.getId());
            cleanupWorkspace(job);
        }
        stepRepo.save(step);
    }

    /**
     * Update the heartbeat timestamp for a running step.
     * Called periodically by the worker thread to prove liveness (§8.2).
     */
    @Transactional
    public void heartbeat(Step step) {
        step.setHeartbeatAt(Instant.now());
        stepRepo.save(step);
    }

    /**
     * Detect and recover steps whose worker has silently crashed (§8.2).
     *
     * A step is considered stalled when it has been RUNNING for more than
     * STALL_TIMEOUT without a heartbeat update. The step is reset to PENDING
     * (or permanently FAILED if it has exhausted its retry attempts) so the
     * scheduler can pick it up again on the next tick.
     *
     * Called by StepScheduler every 60 seconds.
     */
    @Transactional
    public void recoverStalledSteps() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        List<Step> stalled = stepRepo.findByStateAndHeartbeatAtBefore(StepState.RUNNING, cutoff);
        for (Step step : stalled) {
            log.warn("Recovering stalled step {} (worker={}, last heartbeat={})",
                    step.getId(), step.getWorkerId(), step.getHeartbeatAt());
            failStep(step, "Worker heartbeat timed out after 5 minutes");
        }
    }

    /**
     * Return all steps for a job in creation order.
     * Used by GET /jobs/{id}/steps.
     */
    @Transactional(readOnly = true)
    public List<Step> getSteps(UUID jobId) {
        return stepRepo.findByJobIdOrderByCreatedAtAsc(jobId);
    }

    /**
     * Collect all DONE step results for a job, keyed by role.
     * Used by AgentLoop to build context for subsequent agents.
     *
     * After backtracking there can be multiple DONE steps with the same role
     * (e.g. two PLANNER steps). We keep the latest result per role so agents
     * always see the most recent prior work.
     */
    @Transactional(readOnly = true)
    public Map<AgentRole, String> completedResults(UUID jobId) {
        return stepRepo.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .filter(s -> s.getState() == StepState.DONE && s.getResultJson() != null)
                .collect(Collectors.toMap(
                        Step::getRole,
                        Step::getResultJson,
                        (existing, replacement) -> replacement   // keep latest per role
                ));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Delete the executor workspace for a job that has reached a terminal state.
     *
     * Called inside @Transactional methods, so we must swallow any executor
     * error here — a failed delete must never roll back the DB transaction.
     * The job state is already committed before this runs; a leftover workspace
     * only costs disk space and can be cleaned up manually if needed.
     */
    private void cleanupWorkspace(Job job) {
        try {
            workspaceClient.deleteWorkspace(job.getWorkspaceRef());
            log.info("Workspace {} deleted for job {}", job.getWorkspaceRef(), job.getId());
        } catch (Exception e) {
            log.warn("Could not delete workspace {} for job {} — manual cleanup may be needed: {}",
                    job.getWorkspaceRef(), job.getId(), e.getMessage());
        }
    }

    private static AgentRole nextRole(AgentRole current) {
        int idx = PIPELINE.indexOf(current);
        return (idx >= 0 && idx < PIPELINE.size() - 1) ? PIPELINE.get(idx + 1) : null;
    }

    private static JobState jobStateFor(AgentRole role) {
        return switch (role) {
            case REPO_MAPPER  -> JobState.MAP_REPO;
            case PLANNER      -> JobState.PLAN;
            case IMPLEMENTER  -> JobState.IMPLEMENT;
            case TESTER       -> JobState.TEST;
            case REVIEWER     -> JobState.REVIEW;
        };
    }

    /**
     * Check whether the TESTER's result JSON reports tests_passed=true.
     *
     * We do a simple substring search rather than full JSON parsing to
     * avoid a Jackson dependency in the service layer. The TESTER prompt
     * defines the exact field name so the format is controlled.
     */
    private static boolean parseTestsPassed(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return false;
        return resultJson.contains("\"tests_passed\":true")
            || resultJson.contains("\"tests_passed\": true");
    }
}
