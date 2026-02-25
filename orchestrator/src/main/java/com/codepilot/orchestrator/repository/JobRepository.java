package com.codepilot.orchestrator.repository;

import com.codepilot.orchestrator.model.Job;
import com.codepilot.orchestrator.model.JobState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * CRUD + query operations for the jobs table.
 *
 * Spring Data JPA generates the implementation at startup —
 * we only declare the method signatures we need.
 */
public interface JobRepository extends JpaRepository<Job, UUID> {

    /** Find all jobs currently in a given state (used for monitoring). */
    List<Job> findByState(JobState state);
}
