"""
Workspace lifecycle management for the executor service.

A 'workspace' is a directory under WORKSPACE_BASE that holds a cloned copy
of the target repository. Every repair job gets its own workspace so that
concurrent jobs cannot interfere with each other.

Workspace lifecycle (§4.1):
  create   → git clone the repo at the requested ref
  snapshot → tar the workspace into a compressed archive (for rollback)
  restore  → delete current workspace, untar a previous snapshot
  delete   → remove the workspace directory when the job finishes

Snapshots are stored locally under WORKSPACE_BASE/snapshots/.
In K8s (§12.1) these would be uploaded to MinIO — that upgrade only
requires changing snapshot() and restore() to call the object store
instead of writing local files. Everything else stays the same.
"""
import shutil
import subprocess
import time
from pathlib import Path

# ------------------------------------------------------------------
# Directory layout
# ------------------------------------------------------------------

WORKSPACE_BASE = Path("/tmp/codepilot-workspaces")
SNAPSHOTS_DIR  = WORKSPACE_BASE / "snapshots"


# ------------------------------------------------------------------
# Internal helpers
# ------------------------------------------------------------------

def _safe_workspace_path(workspace_ref: str) -> Path:
    """
    Resolve workspace_ref to an absolute path inside WORKSPACE_BASE.

    Raises ValueError if workspace_ref contains path-traversal sequences
    (e.g. "../../../etc") that would escape the base directory.
    """
    base = WORKSPACE_BASE.resolve()
    resolved = (base / workspace_ref).resolve()
    if not str(resolved).startswith(str(base)):
        raise ValueError(
            f"workspace_ref '{workspace_ref}' resolves outside workspace base. "
            "Path traversal is not allowed."
        )
    return resolved


def _run(cmd: list[str], cwd: Path | None = None, timeout: int = 300) -> subprocess.CompletedProcess:
    """Run a command, return the CompletedProcess. Raises RuntimeError on non-zero exit."""
    result = subprocess.run(
        cmd, cwd=cwd,
        capture_output=True, text=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"Command {cmd[0]!r} failed (exit {result.returncode}):\n"
            f"stdout: {result.stdout.strip()}\n"
            f"stderr: {result.stderr.strip()}"
        )
    return result


# ------------------------------------------------------------------
# Public API
# ------------------------------------------------------------------

def create_workspace(workspace_ref: str, repo_url: str, git_ref: str = "HEAD") -> None:
    """
    Clone 'repo_url' at 'git_ref' into a new workspace directory.

    The clone is shallow (--depth 1) when git_ref is a branch or tag name,
    which is much faster for large repos like Guava or Commons. For a full
    commit SHA we fall back to a full clone then checkout, because shallow
    clones with arbitrary SHA require git 2.11+ fetch tricks that are
    fragile across hosting providers.

    Raises:
        FileExistsError  — workspace_ref already exists
        RuntimeError     — git clone or checkout failed (stderr included)
    """
    workspace_dir = _safe_workspace_path(workspace_ref)

    if workspace_dir.exists():
        raise FileExistsError(
            f"Workspace '{workspace_ref}' already exists at {workspace_dir}. "
            "Call DELETE /workspace/{workspace_ref} first."
        )

    WORKSPACE_BASE.mkdir(parents=True, exist_ok=True)

    # Attempt a shallow clone. If git_ref looks like a full 40-char SHA,
    # shallow clone is skipped because --branch doesn't accept raw SHAs.
    looks_like_sha = len(git_ref) == 40 and all(c in "0123456789abcdefABCDEF" for c in git_ref)

    try:
        if looks_like_sha:
            # Full clone then checkout — the only reliable way for arbitrary SHAs.
            _run(["git", "clone", repo_url, str(workspace_dir)], timeout=600)
            _run(["git", "checkout", git_ref], cwd=workspace_dir, timeout=60)
        else:
            # Shallow clone at the named branch/tag — fast path.
            _run(
                ["git", "clone", "--depth", "1", "--branch", git_ref, repo_url, str(workspace_dir)],
                timeout=600,
            )
    except Exception:
        # Remove the partially-cloned directory so the workspace_ref is free to retry.
        shutil.rmtree(workspace_dir, ignore_errors=True)
        raise


def delete_workspace(workspace_ref: str) -> None:
    """
    Permanently delete a workspace directory.

    Called by the orchestrator when a job reaches a terminal state
    (DONE or permanently FAILED) to free disk space.

    Raises:
        FileNotFoundError — workspace does not exist
    """
    workspace_dir = _safe_workspace_path(workspace_ref)
    if not workspace_dir.exists():
        raise FileNotFoundError(
            f"Workspace '{workspace_ref}' not found at {workspace_dir}."
        )
    shutil.rmtree(workspace_dir)


def snapshot_workspace(workspace_ref: str) -> tuple[str, int]:
    """
    Create a compressed tar archive of the workspace.

    Returns (snapshot_key, size_bytes).

    'snapshot_key' is an opaque string — the orchestrator stores it and
    passes it verbatim to restore_workspace() if a rollback is needed.

    The snapshot captures everything in the workspace directory, including
    staged-but-not-committed changes, new untracked files written by the
    agent, and the .git directory — so restore() produces an exact replica.

    Raises:
        FileNotFoundError — workspace does not exist
        RuntimeError      — tar command failed
    """
    workspace_dir = _safe_workspace_path(workspace_ref)
    if not workspace_dir.exists():
        raise FileNotFoundError(
            f"Workspace '{workspace_ref}' not found at {workspace_dir}."
        )

    SNAPSHOTS_DIR.mkdir(parents=True, exist_ok=True)

    # snapshot_key encodes both the workspace_ref and a timestamp so that
    # multiple snapshots of the same workspace can coexist.
    snapshot_key  = f"{workspace_ref}-{int(time.time())}"
    snapshot_path = SNAPSHOTS_DIR / f"{snapshot_key}.tar.gz"

    # tar -czf archive.tar.gz -C /tmp/codepilot-workspaces <workspace_ref>
    # The "-C parent" trick makes paths inside the archive relative,
    # so restore simply untars into the same base directory.
    _run(
        ["tar", "-czf", str(snapshot_path), "-C", str(WORKSPACE_BASE), workspace_ref],
        timeout=120,
    )

    size_bytes = snapshot_path.stat().st_size
    return snapshot_key, size_bytes


def restore_workspace(workspace_ref: str, snapshot_key: str) -> None:
    """
    Replace the current workspace with a previously taken snapshot.

    Steps:
      1. Locate the snapshot archive by snapshot_key.
      2. Delete the current workspace directory (if it exists).
      3. Untar the snapshot into WORKSPACE_BASE — this recreates the
         workspace directory with the exact state from snapshot time.

    Raises:
        FileNotFoundError — snapshot_key not found
        RuntimeError      — tar command failed
    """
    snapshot_path = SNAPSHOTS_DIR / f"{snapshot_key}.tar.gz"
    if not snapshot_path.exists():
        raise FileNotFoundError(
            f"Snapshot '{snapshot_key}' not found at {snapshot_path}. "
            "The key may have expired or been deleted."
        )

    workspace_dir = _safe_workspace_path(workspace_ref)

    # Remove current state so untar recreates a clean directory.
    if workspace_dir.exists():
        shutil.rmtree(workspace_dir)

    # tar -xzf archive.tar.gz -C /tmp/codepilot-workspaces
    # This recreates the <workspace_ref>/ subdirectory inside WORKSPACE_BASE.
    _run(
        ["tar", "-xzf", str(snapshot_path), "-C", str(WORKSPACE_BASE)],
        timeout=120,
    )
