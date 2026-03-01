#!/usr/bin/env python3
"""
benchmark/evaluate.py — BugFixBench-Java-25 evaluator.

Reads results.json (produced by run.py) and tasks.json, then:
  1. For each completed job, inspects the TESTER agent's resultJson to
     determine whether the target test passed after the fix was applied.
  2. Prints a per-task pass/fail table and overall pass@1 metric.
  3. Saves a detailed evaluation report to eval_report.json.

The TESTER agent is expected to produce a resultJson with at least:
  { "tests_run": N, "failures": N, "errors": N, "passed": true|false }

Usage:
    python evaluate.py
    python evaluate.py --results results.json
"""

import argparse
import json
import sys
from pathlib import Path

TASKS_FILE   = Path(__file__).parent / "tasks.json"
RESULTS_FILE = Path(__file__).parent / "results.json"
REPORT_FILE  = Path(__file__).parent / "eval_report.json"

# Column widths for the summary table
COL = {"id": 8, "state": 8, "agent_done": 11, "test_pass": 10, "note": 40}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def load_json(path: Path) -> dict | list:
    with path.open() as f:
        return json.load(f)


def find_step(steps: list[dict], role: str) -> dict | None:
    """Return the first step matching the given role, or None."""
    return next((s for s in steps if s.get("role") == role), None)


def parse_tester_result(result_json: str | None) -> tuple[bool, str]:
    """
    Extract pass/fail from the TESTER agent's resultJson.

    Returns (passed: bool, note: str).
    The agent is instructed to produce JSON with a "passed" boolean field.
    """
    if not result_json:
        return False, "no resultJson"
    try:
        data = json.loads(result_json)
    except json.JSONDecodeError:
        # Agent may have wrapped JSON in markdown; try to extract it.
        import re
        m = re.search(r"\{.*\}", result_json, re.DOTALL)
        if not m:
            return False, "unparseable resultJson"
        try:
            data = json.loads(m.group())
        except json.JSONDecodeError:
            return False, "unparseable resultJson"

    # Accept both "passed" and "tests_passed" (agents use either field name)
    passed_val = data.get("passed", data.get("tests_passed"))
    if passed_val is not None:
        passed = bool(passed_val)
        failures = data.get("failures", "?")
        errors   = data.get("errors", "?")
        tests    = data.get("tests_run", "?")
        note = f"tests={tests} fail={failures} err={errors}"
        return passed, note

    # Fallback: look for "BUILD SUCCESS" string in any field.
    text = json.dumps(data)
    if "BUILD SUCCESS" in text:
        return True, "BUILD SUCCESS in output"
    if "BUILD FAILURE" in text:
        return False, "BUILD FAILURE in output"

    return False, "no 'passed' field in resultJson"


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="BugFixBench evaluator")
    parser.add_argument("--results", default=str(RESULTS_FILE),
                        help=f"Path to results.json (default: {RESULTS_FILE})")
    args = parser.parse_args()

    results_path = Path(args.results)
    if not results_path.exists():
        print(f"ERROR: results file not found: {results_path}", file=sys.stderr)
        print("Run benchmark/run.py first.", file=sys.stderr)
        sys.exit(1)

    tasks_by_id = {t["id"]: t for t in load_json(TASKS_FILE)["tasks"]}
    run_data    = load_json(results_path)
    job_results = run_data.get("results", [])

    # -----------------------------------------------------------------------
    # Print header
    # -----------------------------------------------------------------------
    sep = "-" * 85
    print(f"\n{'BugFixBench Evaluation Report':^85}")
    print(f"{'Orchestrator: ' + run_data.get('orchestrator', '?'):^85}")
    print(sep)
    print(f"{'Task':8}  {'Job state':8}  {'All done':11}  {'Tests pass':10}  {'Note'}")
    print(sep)

    rows       = []
    pass_count = 0
    total      = 0

    for result in job_results:
        task_id = result["task_id"]
        job_id  = result.get("job_id", "—")
        state   = result.get("state", "?")
        steps   = result.get("steps", [])

        # Did all 6 agents finish?
        done_roles  = {s["role"] for s in steps if s.get("state") == "DONE"}
        all_done    = {"REPO_MAPPER", "PLANNER", "IMPLEMENTER", "TESTER", "REVIEWER", "FINALIZER"} \
                      <= done_roles
        all_done_str = "yes" if all_done else "no"

        # Did the target test pass?
        tester_step  = find_step(steps, "TESTER")
        test_passed, note = parse_tester_result(
            tester_step.get("resultJson") if tester_step else None
        )
        test_pass_str = "PASS" if test_passed else "FAIL"
        if state != "DONE":
            test_pass_str = "—"
            note = f"job {state}"

        total += 1
        if test_passed:
            pass_count += 1

        print(f"{task_id:8}  {state:8}  {all_done_str:11}  {test_pass_str:10}  {note[:40]}")
        rows.append({
            "task_id":     task_id,
            "job_id":      job_id,
            "job_state":   state,
            "all_done":    all_done,
            "test_passed": test_passed,
            "note":        note,
            "done_roles":  sorted(done_roles),
        })

    # -----------------------------------------------------------------------
    # Summary
    # -----------------------------------------------------------------------
    print(sep)
    pass_at_1 = pass_count / total if total else 0.0
    print(f"\nTotal tasks : {total}")
    print(f"Tests PASS  : {pass_count}")
    print(f"Pass@1      : {pass_at_1:.1%}\n")

    # -----------------------------------------------------------------------
    # Save report
    # -----------------------------------------------------------------------
    report = {
        "run_started":  run_data.get("started_at"),
        "run_finished": run_data.get("finished_at"),
        "orchestrator": run_data.get("orchestrator"),
        "total":        total,
        "pass_count":   pass_count,
        "pass_at_1":    round(pass_at_1, 4),
        "rows":         rows,
    }
    REPORT_FILE.write_text(json.dumps(report, indent=2))
    print(f"Full report → {REPORT_FILE}")


if __name__ == "__main__":
    main()
