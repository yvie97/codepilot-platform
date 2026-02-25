package com.codepilot.orchestrator.repository;

import com.codepilot.orchestrator.model.Step;
import com.codepilot.orchestrator.model.StepState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + scheduler queries for the steps table.
 */
public interface StepRepository extends JpaRepository<Step, UUID> {

    /**
     * Claim the oldest PENDING step — the core of the at-least-once scheduler (§4.3).
     *
     * SELECT FOR UPDATE SKIP LOCKED means:
     *   - FOR UPDATE  : lock the row so no other transaction can see it as PENDING
     *   - SKIP LOCKED : if the row is already locked by another worker, skip it
     *                   and try the next one — no blocking, no deadlocks.
     *
     * This query must run inside a @Transactional method in the service layer.
     * The caller is responsible for immediately setting state=RUNNING and
     * worker_id before the transaction commits, otherwise the lock is released.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM Step s
            WHERE s.state = 'PENDING'
            ORDER BY s.createdAt ASC
            LIMIT 1
            """)
    Optional<Step> claimNextPendingStep();

    /** All steps for a given job, in creation order. */
    List<Step> findByJobIdOrderByCreatedAtAsc(UUID jobId);

    /**
     * Find RUNNING steps whose heartbeat is older than 'cutoff'.
     * The scheduler uses this to detect and reset crashed workers (§8.2).
     */
    List<Step> findByStateAndHeartbeatAtBefore(StepState state, Instant cutoff);
}
