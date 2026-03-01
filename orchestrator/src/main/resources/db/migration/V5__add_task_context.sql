-- Stores the human-readable task description and the name of the failing
-- test that the agent pipeline must fix. Both are optional (NULL = not provided)
-- so that ad-hoc curl submissions without these fields still work.
ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS task_description TEXT,
    ADD COLUMN IF NOT EXISTS failing_test      TEXT;
