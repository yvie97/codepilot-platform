-- V4: Store the executor snapshot key for workspace rollback (§8.4).
--
-- snapshot_key is set just before IMPLEMENTER runs.  If the TESTER fails
-- and the orchestrator backtracks to PLANNER, the workspace is restored
-- to this snapshot so PLANNER sees a clean (pre-implementation) codebase.
--
-- NULL until the first IMPLEMENTER step begins.
ALTER TABLE jobs ADD COLUMN snapshot_key TEXT;
