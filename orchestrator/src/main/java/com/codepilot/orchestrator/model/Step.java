package com.codepilot.orchestrator.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent-role execution within a Job.
 *
 * The scheduler claims a PENDING step via SELECT FOR UPDATE SKIP LOCKED,
 * sets state = RUNNING and worker_id, then a worker thread runs the
 * agent loop and writes result_json on completion (§4.3).
 *
 * DB table: steps  (created by Flyway V1 migration)
 */
@Entity
@Table(name = "steps")
public class Step {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Many steps belong to one job.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepState state = StepState.PENDING;

    // How many times this step has been attempted (starts at 0).
    @Column(nullable = false)
    private int attempt = 0;

    // Identifies which worker thread is running this step.
    // Null when state = PENDING or DONE.
    @Column(name = "worker_id")
    private String workerId;

    // Updated periodically by the worker to prove liveness (§8.2).
    // The scheduler resets stale steps whose heartbeat is too old.
    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    // The agent's final <result> block, JSON-encoded.
    // Passed to the next step as context.
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    // Full conversation history as a JSON array of {role, content} objects (§5.3).
    // Saved after every agent turn so a crashed worker can resume mid-step.
    @Column(name = "conversation_history", columnDefinition = "TEXT")
    private String conversationHistory;

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    protected Step() {}   // required by JPA

    public Step(Job job, AgentRole role) {
        this.job  = job;
        this.role = role;
    }

    // ------------------------------------------------------------------
    // Getters / setters
    // ------------------------------------------------------------------

    public UUID       getId()          { return id; }
    public Job        getJob()         { return job; }
    public AgentRole  getRole()        { return role; }
    public StepState  getState()       { return state; }
    public int        getAttempt()     { return attempt; }
    public String     getWorkerId()    { return workerId; }
    public Instant    getHeartbeatAt() { return heartbeatAt; }
    public Instant    getCreatedAt()   { return createdAt; }
    public Instant    getStartedAt()   { return startedAt; }
    public Instant    getFinishedAt()  { return finishedAt; }
    public String     getResultJson()  { return resultJson; }

    public void setState(StepState state)                         { this.state = state; }
    public void setWorkerId(String workerId)                       { this.workerId = workerId; }
    public void setHeartbeatAt(Instant t)                          { this.heartbeatAt = t; }
    public void setStartedAt(Instant t)                            { this.startedAt = t; }
    public void setFinishedAt(Instant t)                           { this.finishedAt = t; }
    public void setResultJson(String resultJson)                    { this.resultJson = resultJson; }
    public void setConversationHistory(String conversationHistory)  { this.conversationHistory = conversationHistory; }
    public void incrementAttempt()                                  { this.attempt++; }

    public String getConversationHistory() { return conversationHistory; }
}
