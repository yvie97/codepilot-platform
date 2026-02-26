package com.codepilot.orchestrator.service;

import com.codepilot.orchestrator.agent.AgentLoop;
import com.codepilot.orchestrator.model.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background scheduler that drives the agent pipeline.
 *
 * Every 2 seconds, it tries to claim a PENDING step from the DB
 * and runs it in a worker thread pool.
 *
 * Why a fixed-delay loop instead of a queue?
 *   The DB IS the queue. SELECT FOR UPDATE SKIP LOCKED is the dequeue.
 *   This means no Kafka/RabbitMQ needed — fewer moving parts (§2).
 *
 * Why not use @Async?
 *   @Async creates unbounded threads. We use a fixed pool to cap
 *   concurrency and avoid overwhelming the executor service or Claude API.
 */
@Component
@EnableScheduling
public class StepScheduler {

    private static final Logger log = LoggerFactory.getLogger(StepScheduler.class);

    // Each worker runs one agent step at a time.
    // 4 workers = up to 4 concurrent Claude conversations.
    private static final int WORKER_COUNT = 4;

    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT);

    private final JobService jobService;
    private final AgentLoop  agentLoop;

    public StepScheduler(JobService jobService, AgentLoop agentLoop) {
        this.jobService = jobService;
        this.agentLoop  = agentLoop;
    }

    /**
     * Tick: claim one PENDING step (if any) and dispatch it to a worker thread.
     *
     * fixedDelay = 2000 ms means: wait 2 s after the previous tick finishes
     * before starting the next one. This avoids hammering the DB if there's
     * nothing to do.
     *
     * Each tick only claims ONE step. If there are multiple PENDING steps,
     * subsequent ticks (or other scheduler pods in K8s) will claim the rest.
     */
    @Scheduled(fixedDelay = 2000)
    public void tick() {
        String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

        Optional<Step> claimed = jobService.claimNextStep(workerId);
        claimed.ifPresent(step ->
                workers.submit(() -> {
                    try {
                        agentLoop.run(step);
                    } catch (Exception e) {
                        log.error("Unhandled error in agent loop for step {}: {}",
                                step.getId(), e.getMessage(), e);
                        jobService.failStep(step, "Unhandled exception: " + e.getMessage());
                    }
                })
        );
    }
}
