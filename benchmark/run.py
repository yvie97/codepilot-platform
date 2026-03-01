#!/usr/bin/env python3
"""
benchmark/run.py — BugFixBench-Java-25 runner.

Submits every task in tasks.json to the CodePilot orchestrator,
polls until each job reaches a terminal state (DONE or FAILED),
then writes results.json with the outcome of every job.

Usage:
    python run.py                         # run all tasks
    python run.py --tasks LANG-1 TEXT-1   # run specific tasks by id
    python run.py --orchestrator http://localhost:8080

Output: results.json (one entry per task, with job_id, final state, step results)
"""

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

ORCHESTRATOR_DEFAULT = "http://localhost:8080"
TASKS_FILE           = Path(__file__).parent / "tasks.json"
RESULTS_FILE         = Path(__file__).parent / "results.json"
POLL_INTERVAL_SEC    = 15
JOB_TIMEOUT_SEC      = 7200   # 2 hours max per job


# ---------------------------------------------------------------------------
# API helpers
# ---------------------------------------------------------------------------

def submit_job(base_url: str, repo_url: str, git_ref: str,
               task_description: str | None = None,
               failing_test: str | None = None) -> str:
    """POST /jobs and return the new job UUID."""
    payload: dict = {"repoUrl": repo_url, "gitRef": git_ref}
    if task_description:
        payload["taskDescription"] = task_description
    if failing_test:
        payload["failingTest"] = failing_test
    resp = requests.post(f"{base_url}/jobs", json=payload, timeout=30)
    resp.raise_for_status()
    return resp.json()["id"]


def get_job(base_url: str, job_id: str) -> dict:
    """GET /jobs/{id}."""
    resp = requests.get(f"{base_url}/jobs/{job_id}", timeout=10)
    resp.raise_for_status()
    return resp.json()


def get_steps(base_url: str, job_id: str) -> list[dict]:
    """GET /jobs/{id}/steps."""
    resp = requests.get(f"{base_url}/jobs/{job_id}/steps", timeout=10)
    resp.raise_for_status()
    return resp.json()


def poll_until_done(base_url: str, job_id: str, timeout_sec: int) -> dict | None:
    """
    Block until the job reaches DONE or FAILED, then return the job dict.
    Returns None on timeout.
    """
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        job = get_job(base_url, job_id)
        state = job["state"]
        if state in ("DONE", "FAILED"):
            return job
        print(f"    state={state} …", flush=True)
        time.sleep(POLL_INTERVAL_SEC)
    return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="BugFixBench runner")
    parser.add_argument("--orchestrator", default=ORCHESTRATOR_DEFAULT,
                        help=f"Orchestrator base URL (default: {ORCHESTRATOR_DEFAULT})")
    parser.add_argument("--tasks", nargs="*",
                        help="Task IDs to run (default: all tasks in tasks.json)")
    args = parser.parse_args()

    base_url = args.orchestrator.rstrip("/")

    # Load task definitions
    with TASKS_FILE.open() as f:
        all_tasks = json.load(f)["tasks"]

    if args.tasks:
        tasks = [t for t in all_tasks if t["id"] in args.tasks]
        missing = set(args.tasks) - {t["id"] for t in tasks}
        if missing:
            print(f"ERROR: unknown task ids: {missing}", file=sys.stderr)
            sys.exit(1)
    else:
        tasks = all_tasks

    print(f"Running {len(tasks)} task(s) against {base_url}\n")

    results = []
    started_at = datetime.now(timezone.utc).isoformat()

    for task in tasks:
        task_id = task["id"]
        print(f"[{task_id}] {task['description'][:80]}")

        # Prefer repo_url_local (file:// path) when repo_url is a PLACEHOLDER
        repo_url = task["repo_url"]
        if repo_url.startswith("PLACEHOLDER") and "repo_url_local" in task:
            repo_url = task["repo_url_local"]
            print(f"  (using local repo: {repo_url})")

        print(f"  repo={repo_url}  ref={task['git_ref']}")

        # Submit — include task description and failing test so agents know what to fix
        try:
            job_id = submit_job(base_url, repo_url, task["git_ref"],
                                task_description=task.get("description"),
                                failing_test=task.get("failing_test"))
        except Exception as e:
            print(f"  ERROR submitting: {e}")
            results.append({"task_id": task_id, "job_id": None,
                            "state": "SUBMIT_ERROR", "error": str(e)})
            continue

        print(f"  job_id={job_id}")

        # Poll
        job = poll_until_done(base_url, job_id, JOB_TIMEOUT_SEC)
        if job is None:
            print(f"  TIMEOUT after {JOB_TIMEOUT_SEC}s")
            results.append({"task_id": task_id, "job_id": job_id,
                            "state": "TIMEOUT", "steps": []})
            continue

        # Collect step results
        try:
            steps = get_steps(base_url, job_id)
        except Exception:
            steps = []

        state = job["state"]
        print(f"  ⟶  {state} ({len(steps)} steps)")
        results.append({
            "task_id":    task_id,
            "job_id":     job_id,
            "state":      state,
            "created_at": job.get("createdAt"),
            "updated_at": job.get("updatedAt"),
            "steps":      steps,
        })

    # Save results
    output = {
        "started_at":    started_at,
        "finished_at":   datetime.now(timezone.utc).isoformat(),
        "orchestrator":  base_url,
        "task_count":    len(tasks),
        "done_count":    sum(1 for r in results if r["state"] == "DONE"),
        "failed_count":  sum(1 for r in results if r["state"] == "FAILED"),
        "results":       results,
    }
    RESULTS_FILE.write_text(json.dumps(output, indent=2))
    print(f"\nSaved → {RESULTS_FILE}")
    print(f"DONE={output['done_count']}  FAILED={output['failed_count']}  "
          f"OTHER={len(results) - output['done_count'] - output['failed_count']}")


if __name__ == "__main__":
    main()
