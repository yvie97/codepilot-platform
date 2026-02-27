-- Backtracking support (§4.2):
--   consecutive_test_failures  counts how many times in a row the TESTER has
--                              reported tests_passed=false without a success in
--                              between. When this reaches 2, the job is marked
--                              FAILED (backtrack budget exhausted).
--   iteration_count            total number of PLAN→IMPLEMENT→TEST cycles
--                              the job has gone through (informational / metrics).
ALTER TABLE jobs
    ADD COLUMN consecutive_test_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN iteration_count           INT NOT NULL DEFAULT 0;
