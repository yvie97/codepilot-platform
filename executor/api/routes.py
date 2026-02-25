"""
FastAPI route handlers for the executor service.

Routes:
  POST   /workspace/create           — clone a repo into a new workspace
  POST   /workspace/snapshot         — tar the workspace for rollback
  POST   /workspace/restore          — untar a snapshot, replacing current state
  DELETE /workspace/{workspace_ref}  — delete a workspace when the job ends
  POST   /workspace/run_code         — execute agent-emitted Python in sandbox
  GET    /workspace/health           — liveness probe
"""
from pathlib import Path

from fastapi import APIRouter, HTTPException

from models import (
    RunCodeRequest, ExecutionResult,
    CreateWorkspaceRequest, WorkspaceResponse,
    SnapshotRequest, SnapshotResponse,
    RestoreRequest,
)
from sandbox.runner import run_code
from workspace.manager import (
    WORKSPACE_BASE,
    create_workspace,
    delete_workspace,
    snapshot_workspace,
    restore_workspace,
)

router = APIRouter(prefix="/workspace")


# ------------------------------------------------------------------
# Workspace lifecycle
# ------------------------------------------------------------------

@router.post("/create", response_model=WorkspaceResponse)
def handle_create(req: CreateWorkspaceRequest) -> WorkspaceResponse:
    """
    Clone a git repository into a new workspace directory (§4.1).

    Called once when a repair job is created. The orchestrator passes the
    workspace_ref (job UUID) as a stable identifier for all subsequent calls.
    """
    try:
        create_workspace(req.workspace_ref, req.repo_url, req.git_ref)
    except FileExistsError as e:
        raise HTTPException(status_code=409, detail=str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return WorkspaceResponse(
        workspace_ref=req.workspace_ref,
        success=True,
        message=f"Cloned {req.repo_url} @ {req.git_ref}",
    )


@router.post("/snapshot", response_model=SnapshotResponse)
def handle_snapshot(req: SnapshotRequest) -> SnapshotResponse:
    """
    Create a compressed snapshot of the workspace (§8.4).

    Called before the Implementer agent runs so the orchestrator can roll
    back a failed attempt without re-cloning the repo from scratch.
    """
    try:
        snapshot_key, size_bytes = snapshot_workspace(req.workspace_ref)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return SnapshotResponse(
        workspace_ref=req.workspace_ref,
        snapshot_key=snapshot_key,
        size_bytes=size_bytes,
    )


@router.post("/restore", response_model=WorkspaceResponse)
def handle_restore(req: RestoreRequest) -> WorkspaceResponse:
    """
    Roll a workspace back to a previously taken snapshot (§8.4).

    The current workspace directory is deleted and replaced with the
    snapshot contents. The orchestrator calls this when an implementation
    attempt fails and needs to retry from a clean state.
    """
    try:
        restore_workspace(req.workspace_ref, req.snapshot_key)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return WorkspaceResponse(
        workspace_ref=req.workspace_ref,
        success=True,
        message=f"Restored from snapshot '{req.snapshot_key}'",
    )


@router.delete("/{workspace_ref}", response_model=WorkspaceResponse)
def handle_delete(workspace_ref: str) -> WorkspaceResponse:
    """
    Delete a workspace directory when the job reaches a terminal state.

    Called by the orchestrator once a job is DONE or permanently FAILED
    to free disk space on the executor pod.
    """
    try:
        delete_workspace(workspace_ref)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return WorkspaceResponse(
        workspace_ref=workspace_ref,
        success=True,
        message="Workspace deleted.",
    )


# ------------------------------------------------------------------
# Code execution (§7)
# ------------------------------------------------------------------

@router.post("/run_code", response_model=ExecutionResult)
def handle_run_code(req: RunCodeRequest) -> ExecutionResult:
    """
    Execute a Python code action in the sandbox (§7.1).

    Called by the orchestrator on every turn of the agent loop.
    The returned ExecutionResult is converted to an observation string
    and appended to the agent's conversation history.

    Note: workspace must already exist (via POST /workspace/create).
    We auto-create it here as a convenience for local testing, but in
    production the orchestrator always calls /create first.
    """
    workspace_dir = WORKSPACE_BASE / req.workspace_ref
    workspace_dir.mkdir(parents=True, exist_ok=True)

    return run_code(
        code=req.code,
        workspace_dir=workspace_dir,
        timeout_sec=req.timeout_sec,
    )


# ------------------------------------------------------------------
# Health / liveness probe
# ------------------------------------------------------------------

@router.get("/health")
def health() -> dict:
    """Simple liveness probe — used by K8s readinessProbe."""
    return {"status": "ok"}
