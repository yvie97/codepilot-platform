-- =============================================================
-- V1: Initial schema for CodePilot orchestrator
--
-- Two tables:
--   jobs  — one row per repair task submitted by the user
--   steps — one row per agent role within a job
--
-- State machines:
--   Job:  INIT → MAP_REPO → PLAN → IMPLEMENT → TEST → REVIEW
--              → FINALIZE → DONE  (or FAILED at any point)
--   Step: PENDING → RUNNING → DONE  (or FAILED)
-- =============================================================

CREATE TABLE jobs (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_url      TEXT        NOT NULL,
    git_ref       TEXT        NOT NULL DEFAULT 'main',

    -- Current stage of the overall repair pipeline (§4.2)
    state         TEXT        NOT NULL DEFAULT 'INIT',

    -- Opaque identifier used in all executor API calls (§4.1).
    -- Set to the job UUID once the workspace is created.
    workspace_ref TEXT,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE steps (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id       UUID        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,

    -- Which agent role this step runs (§5.3)
    role         TEXT        NOT NULL,   -- REPO_MAPPER | PLANNER | IMPLEMENTER | TESTER | REVIEWER

    -- Execution status
    state        TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING | RUNNING | DONE | FAILED
    attempt      INT         NOT NULL DEFAULT 0,          -- incremented on each retry

    -- Claimed by a worker thread when state = RUNNING (§4.3)
    worker_id    TEXT,
    heartbeat_at TIMESTAMPTZ,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,

    -- JSON-encoded output of the step (agent's final <result> block).
    -- The next step's agent receives this as part of its system prompt.
    result_json  TEXT
);

-- Indexes for the scheduler's hot path (§4.3):
--   1. Find unclaimed steps quickly
--   2. Look up all steps for a given job
CREATE INDEX idx_steps_state      ON steps(state) WHERE state = 'PENDING';
CREATE INDEX idx_steps_job_id     ON steps(job_id);
