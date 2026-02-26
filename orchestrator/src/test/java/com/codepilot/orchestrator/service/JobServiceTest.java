package com.codepilot.orchestrator.service;

import com.codepilot.orchestrator.executor.WorkspaceClient;
import com.codepilot.orchestrator.model.*;
import com.codepilot.orchestrator.repository.JobRepository;
import com.codepilot.orchestrator.repository.StepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobService.
 *
 * All I/O (DB + HTTP) is mocked with Mockito — no Spring context,
 * no database, no network. These tests run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock JobRepository   jobRepo;
    @Mock StepRepository  stepRepo;
    @Mock WorkspaceClient workspaceClient;

    JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(jobRepo, stepRepo, workspaceClient);
    }

    // ------------------------------------------------------------------
    // submit()
    // ------------------------------------------------------------------

    @Test
    void submit_happyPath_createsJobAndFirstStep() {
        Job savedJob = jobWithId();
        when(jobRepo.save(any())).thenReturn(savedJob);
        doNothing().when(workspaceClient).createWorkspace(any(), any(), any());

        Job result = service.submit("https://github.com/org/repo.git", "main");

        // Workspace must be created
        verify(workspaceClient).createWorkspace(any(), eq("https://github.com/org/repo.git"), eq("main"));
        // First step must be REPO_MAPPER
        ArgumentCaptor<Step> stepCaptor = ArgumentCaptor.forClass(Step.class);
        verify(stepRepo).save(stepCaptor.capture());
        assertThat(stepCaptor.getValue().getRole()).isEqualTo(AgentRole.REPO_MAPPER);
        // Job state must advance to MAP_REPO
        assertThat(result.getState()).isEqualTo(JobState.MAP_REPO);
    }

    @Test
    void submit_workspaceCreationFails_jobMarkedFailed() {
        Job savedJob = jobWithId();
        when(jobRepo.save(any())).thenReturn(savedJob);
        doThrow(new com.codepilot.orchestrator.executor.ExecutorException("clone failed", null))
                .when(workspaceClient).createWorkspace(any(), any(), any());

        Job result = service.submit("https://github.com/org/repo.git", "main");

        assertThat(result.getState()).isEqualTo(JobState.FAILED);
        verify(stepRepo, never()).save(any());   // no steps created on failure
    }

    // ------------------------------------------------------------------
    // failStep() — retry logic
    // ------------------------------------------------------------------

    @Test
    void failStep_firstAttempt_resetsStepToPending() {
        Step step = stepWithAttempt(0);
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "timeout");

        assertThat(step.getState()).isEqualTo(StepState.PENDING);  // will retry
        assertThat(step.getAttempt()).isEqualTo(1);
        // Job should NOT be marked FAILED yet
        verify(jobRepo, never()).save(argThat(j -> j.getState() == JobState.FAILED));
    }

    @Test
    void failStep_secondAttempt_stillRetries() {
        Step step = stepWithAttempt(1);
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "timeout");

        assertThat(step.getState()).isEqualTo(StepState.PENDING);
        assertThat(step.getAttempt()).isEqualTo(2);
    }

    @Test
    void failStep_thirdAttempt_permanentlyFails() {
        Step step = stepWithAttempt(2);   // already attempted twice before
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "max retries");

        assertThat(step.getState()).isEqualTo(StepState.FAILED);
        // Job must be marked FAILED
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepo).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getState()).isEqualTo(JobState.FAILED);
    }

    // ------------------------------------------------------------------
    // completeStep() — pipeline advancement
    // ------------------------------------------------------------------

    @Test
    void completeStep_repoMapper_advancesToPlanner() {
        Step step = stepForRole(AgentRole.REPO_MAPPER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"repo_map\": true}");

        // Next step must be PLANNER
        ArgumentCaptor<Step> stepCaptor = ArgumentCaptor.forClass(Step.class);
        verify(stepRepo, atLeastOnce()).save(stepCaptor.capture());
        List<Step> saved = stepCaptor.getAllValues();
        boolean plannerCreated = saved.stream()
                .anyMatch(s -> s.getRole() == AgentRole.PLANNER && s.getState() == StepState.PENDING);
        assertThat(plannerCreated).isTrue();

        // Job state must advance to PLAN
        assertThat(job.getState()).isEqualTo(JobState.PLAN);
    }

    @Test
    void completeStep_reviewer_jobMarkedDone() {
        Step step = stepForRole(AgentRole.REVIEWER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"approved\": true}");

        // No new step should be created after the last role
        verify(stepRepo, times(1)).save(any());  // only the REVIEWER step itself
        // Job must be DONE
        assertThat(job.getState()).isEqualTo(JobState.DONE);
    }

    // ------------------------------------------------------------------
    // recoverStalledSteps()
    // ------------------------------------------------------------------

    @Test
    void recoverStalledSteps_withStalledStep_resetsItToPending() {
        Step stalled = stepWithAttempt(0);
        stalled.setState(StepState.RUNNING);
        stalled.setHeartbeatAt(Instant.now().minusSeconds(600));  // 10 min ago

        when(stepRepo.findByStateAndHeartbeatAtBefore(eq(StepState.RUNNING), any()))
                .thenReturn(List.of(stalled));
        when(stepRepo.save(any())).thenReturn(stalled);

        service.recoverStalledSteps();

        // The stalled step should be retried (reset to PENDING)
        assertThat(stalled.getState()).isEqualTo(StepState.PENDING);
    }

    @Test
    void recoverStalledSteps_withNoStalledSteps_doesNothing() {
        when(stepRepo.findByStateAndHeartbeatAtBefore(any(), any()))
                .thenReturn(List.of());

        service.recoverStalledSteps();

        verify(stepRepo, never()).save(any());
        verify(jobRepo,  never()).save(any());
    }

    // ------------------------------------------------------------------
    // Test object factories
    // ------------------------------------------------------------------

    private Job jobWithId() {
        Job job = new Job("https://github.com/org/repo.git", "main");
        // Reflectively set the id since it's normally set by JPA on persist
        try {
            var f = job.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(job, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return job;
    }

    private Step stepWithAttempt(int attempt) {
        Step step = stepForRole(AgentRole.REPO_MAPPER);
        for (int i = 0; i < attempt; i++) step.incrementAttempt();
        return step;
    }

    private Step stepForRole(AgentRole role) {
        Job job = jobWithId();
        job.setWorkspaceRef(job.getId().toString());
        Step step = new Step(job, role);
        try {
            var f = step.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(step, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return step;
    }
}
