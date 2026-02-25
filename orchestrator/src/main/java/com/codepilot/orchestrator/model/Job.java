package com.codepilot.orchestrator.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents one repair task submitted by a user.
 *
 * A Job owns an ordered list of Steps — one per agent role.
 * The orchestrator advances the Job's state as each Step completes.
 *
 * DB table: jobs  (created by Flyway V1 migration)
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "git_ref", nullable = false)
    private String gitRef = "main";

    // Stored as a plain string — the column type is TEXT in Postgres.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobState state = JobState.INIT;

    // Set after the executor creates the workspace (§4.1).
    // Equals the job UUID as a string for simplicity.
    @Column(name = "workspace_ref")
    private String workspaceRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Lazy-loaded: we don't always need the steps when we query a Job.
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<Step> steps = new ArrayList<>();

    // Called automatically by JPA before every UPDATE.
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    protected Job() {}   // required by JPA

    public Job(String repoUrl, String gitRef) {
        this.repoUrl = repoUrl;
        this.gitRef  = gitRef;
    }

    // ------------------------------------------------------------------
    // Getters / setters
    // ------------------------------------------------------------------

    public UUID      getId()           { return id; }
    public String    getRepoUrl()      { return repoUrl; }
    public String    getGitRef()       { return gitRef; }
    public JobState  getState()        { return state; }
    public String    getWorkspaceRef() { return workspaceRef; }
    public Instant   getCreatedAt()    { return createdAt; }
    public Instant   getUpdatedAt()    { return updatedAt; }
    public List<Step> getSteps()       { return steps; }

    public void setState(JobState state)               { this.state = state; }
    public void setWorkspaceRef(String workspaceRef)   { this.workspaceRef = workspaceRef; }
}
