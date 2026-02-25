"""
Request and response models for the executor service.

These Pydantic models define the HTTP contract between the orchestrator (Java)
and the executor (Python). Any change here must be reflected in the Java-side
DTO classes.
"""
from pydantic import BaseModel
from typing import Optional


class RunCodeRequest(BaseModel):
    """
    Sent by the orchestrator when an agent emits a code action (§5.1).

    The 'code' field is the raw Python code block extracted from Claude's
    response. The executor runs it in a sandbox and returns the result as
    the agent's next observation.
    """
    code: str           # Python code block emitted by the agent
    workspace_ref: str  # Identifies which workspace directory to run in
    timeout_sec: int = 60


class ExecutionResult(BaseModel):
    """
    Returned after running a code action.

    The orchestrator converts this into an observation string and appends it
    to the agent's conversation history as a user message:

        conversation.add(Message("user", "Observation:\n" + result.to_observation()))

    That observation is what the agent sees on its next turn — enabling
    self-debugging (§5.1).
    """
    exit_code: int             # 0 = success, non-zero = error
    stdout: str                # Everything the code printed
    stderr: str                # Exception tracebacks and error messages
    elapsed_sec: float         # Actual wall-clock time used
    error_type: Optional[str] = None  # "TIMEOUT" | "POLICY_VIOLATION" | None

    def to_observation(self) -> str:
        """
        Format this result as the observation string the agent will read.

        Keeping the format consistent is important: the agent's prompts are
        written expecting this exact layout. If you change it, update the
        system prompts too.
        """
        parts = []
        if self.stdout.strip():
            parts.append(f"stdout:\n{self.stdout.rstrip()}")
        if self.stderr.strip():
            parts.append(f"stderr:\n{self.stderr.rstrip()}")
        if not parts:
            parts.append("(no output)")
        parts.append(f"exit_code: {self.exit_code}")
        if self.error_type:
            parts.append(f"error_type: {self.error_type}")
        return "\n\n".join(parts)


# ------------------------------------------------------------------
# Workspace lifecycle models
# ------------------------------------------------------------------

class CreateWorkspaceRequest(BaseModel):
    """
    Sent by the orchestrator when a new repair job starts (§4.1).

    The executor clones 'repo_url' at 'git_ref' into a fresh workspace
    directory identified by 'workspace_ref'.
    """
    workspace_ref: str   # Unique ID for this workspace (e.g. job UUID)
    repo_url: str        # Git clone URL (https or ssh)
    git_ref: str = "HEAD"  # Branch, tag, or full commit SHA to check out


class WorkspaceResponse(BaseModel):
    """Generic success/failure response for workspace lifecycle operations."""
    workspace_ref: str
    success: bool
    message: str = ""


class SnapshotRequest(BaseModel):
    """
    Request a point-in-time snapshot of a workspace.

    Called by the orchestrator before the Implementer agent runs, so that
    a failed implementation attempt can be rolled back (§8.4).
    """
    workspace_ref: str


class SnapshotResponse(BaseModel):
    """
    Returned after a successful snapshot.

    'snapshot_key' is an opaque string. The orchestrator stores it and
    passes it back verbatim to POST /workspace/restore if needed.
    """
    workspace_ref: str
    snapshot_key: str     # Opaque key: pass to RestoreRequest.snapshot_key
    size_bytes: int       # Compressed size of the snapshot archive


class RestoreRequest(BaseModel):
    """
    Roll a workspace back to a previously taken snapshot.

    The current workspace directory is deleted and replaced with the
    contents of the snapshot archive identified by 'snapshot_key'.
    """
    workspace_ref: str
    snapshot_key: str   # Returned by POST /workspace/snapshot
