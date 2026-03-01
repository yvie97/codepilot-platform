package com.codepilot.orchestrator.api;

import com.codepilot.orchestrator.model.*;
import com.codepilot.orchestrator.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for JobController.
 *
 * @WebMvcTest spins up only the web layer (no DB, no scheduler, no Claude).
 * JobService is replaced by a mock so we can control its behaviour precisely.
 */
@WebMvcTest(JobController.class)
class JobControllerTest {

    @Autowired MockMvc     mockMvc;
    @MockitoBean JobService jobService;

    // ------------------------------------------------------------------
    // POST /jobs
    // ------------------------------------------------------------------

    @Test
    void submitJob_validRequest_returns201WithJobId() throws Exception {
        Job job = fakeJob(JobState.MAP_REPO);
        when(jobService.submit(any(), any(), any(), any())).thenReturn(job);

        mockMvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repoUrl":"https://github.com/org/repo.git","gitRef":"main"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.state").value("MAP_REPO"));
    }

    @Test
    void submitJob_workspaceFailure_returns201WithFailedState() throws Exception {
        // JobService handles the failure and returns a FAILED job — controller
        // always returns 201 because the job was created (even if it immediately fails).
        Job job = fakeJob(JobState.FAILED);
        when(jobService.submit(any(), any(), any(), any())).thenReturn(job);

        mockMvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repoUrl":"https://github.com/org/bad-repo.git","gitRef":"main"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("FAILED"));
    }

    // ------------------------------------------------------------------
    // GET /jobs/{id}
    // ------------------------------------------------------------------

    @Test
    void getJob_existingId_returns200() throws Exception {
        Job job = fakeJob(JobState.PLAN);
        when(jobService.findById(job.getId())).thenReturn(Optional.of(job));

        mockMvc.perform(get("/jobs/{id}", job.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PLAN"));
    }

    @Test
    void getJob_unknownId_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(jobService.findById(unknown)).thenReturn(Optional.empty());

        mockMvc.perform(get("/jobs/{id}", unknown))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // GET /jobs/{id}/steps
    // ------------------------------------------------------------------

    @Test
    void getSteps_existingJob_returns200WithStepList() throws Exception {
        Job job = fakeJob(JobState.PLAN);
        when(jobService.findById(job.getId())).thenReturn(Optional.of(job));

        Step step = new Step(job, AgentRole.REPO_MAPPER);
        step.setState(StepState.DONE);
        step.setResultJson("{\"files\": 42}");
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step));

        mockMvc.perform(get("/jobs/{id}/steps", job.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("REPO_MAPPER"))
                .andExpect(jsonPath("$[0].state").value("DONE"))
                .andExpect(jsonPath("$[0].resultJson").value("{\"files\": 42}"));
    }

    @Test
    void getSteps_unknownJobId_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(jobService.findById(unknown)).thenReturn(Optional.empty());

        mockMvc.perform(get("/jobs/{id}/steps", unknown))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Job fakeJob(JobState state) {
        Job job = new Job("https://github.com/org/repo.git", "main");
        job.setState(state);
        job.setWorkspaceRef(UUID.randomUUID().toString());
        try {
            var f = job.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(job, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return job;
    }
}
