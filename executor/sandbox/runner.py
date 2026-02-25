"""
Sandbox runner: executes agent-emitted Python code in a restricted environment.

The three layers of defense (§9.4):
  1. validator.py   — AST import check (static, before any execution)
  2. This file      — restricted exec() globals (runtime)
  3. K8s pod policy — non-root, read-only root FS, no network (deployment-time)

This file handles layer 2: we strip dangerous builtins, inject only the
approved tool functions, capture stdout/stderr, and enforce a wall-clock
timeout using a background thread.
"""
import builtins
import contextlib
import io
import time
import traceback
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout
from pathlib import Path

from sandbox.validator import validate_imports, SecurityError
from sandbox.tools import build_tools
from models import ExecutionResult

# ------------------------------------------------------------------
# Builtins allowlist
# ------------------------------------------------------------------

# Names of builtins we explicitly remove from the sandbox.
# Rule of thumb: anything that can read/write arbitrary files, execute
# arbitrary code, or access the network is blocked.
_BLOCKED_BUILTINS = frozenset({
    "__import__",   # import machinery — we control imports via the allowlist
    "eval",         # executes arbitrary expressions
    "exec",         # executes arbitrary code (the agent uses OUR exec, not its own)
    "compile",      # compiles code objects
    "open",         # raw file I/O (replaced with workspace-scoped version below)
    "input",        # reads from stdin (no interactive I/O in sandbox)
    "memoryview",   # low-level memory access
    "breakpoint",   # drops into debugger
})

def _build_safe_builtins(workspace_dir: Path) -> dict:
    """
    Build a filtered builtins dict for the sandbox globals.

    We start from the real builtins and remove dangerous entries, then
    replace 'open' with a workspace-scoped version.
    """
    safe = {
        k: v for k, v in vars(builtins).items()
        if k not in _BLOCKED_BUILTINS
    }

    # Replace open() with a version that is locked to the workspace directory.
    # Any attempt to open a path outside the workspace raises PermissionError.
    ws = workspace_dir.resolve()

    def safe_open(path, mode="r", **kwargs):
        resolved = Path(path).resolve()
        if not str(resolved).startswith(str(ws)):
            raise PermissionError(
                f"open() path '{path}' is outside the workspace. "
                "Use read_file() / write_file() for workspace files."
            )
        return builtins.open(path, mode, **kwargs)

    safe["open"] = safe_open
    return safe


# ------------------------------------------------------------------
# Main entry point
# ------------------------------------------------------------------

def run_code(code: str, workspace_dir: Path, timeout_sec: int = 60) -> ExecutionResult:
    """
    Execute agent-emitted Python code in a restricted sandbox.

    Steps:
      1. Validate imports (static AST check)
      2. Build sandbox globals (restricted builtins + tool functions)
      3. Execute with wall-clock timeout in a background thread
      4. Return stdout, stderr, exit_code as ExecutionResult

    The returned ExecutionResult is converted to an observation string
    by the orchestrator and fed back to the agent as its next input.
    This is the feedback loop that enables self-debugging (§5.1).
    """
    start = time.monotonic()

    # ------------------------------------------------------------------
    # Step 1: Static validation
    # ------------------------------------------------------------------
    try:
        validate_imports(code)
    except SyntaxError as e:
        return ExecutionResult(
            exit_code=1,
            stdout="",
            stderr=f"SyntaxError: {e}",
            elapsed_sec=time.monotonic() - start,
            error_type="SYNTAX_ERROR",
        )
    except SecurityError as e:
        return ExecutionResult(
            exit_code=1,
            stdout="",
            stderr=str(e),
            elapsed_sec=time.monotonic() - start,
            error_type="POLICY_VIOLATION",
        )

    # ------------------------------------------------------------------
    # Step 2: Build sandbox globals
    # ------------------------------------------------------------------
    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()

    tools = build_tools(workspace_dir)

    sandbox_globals = {
        "__builtins__": _build_safe_builtins(workspace_dir),
        # Inject all tool functions directly — the agent calls them
        # like regular functions, no import needed.
        **tools,
    }

    # ------------------------------------------------------------------
    # Step 3: Execute in a background thread with timeout
    #
    # Why a thread instead of signal.SIGALRM?
    #   SIGALRM only works in the main thread, and FastAPI uses a thread
    #   pool for request handlers. ThreadPoolExecutor.result(timeout=...)
    #   works correctly in any thread.
    #
    # Note: we cannot forcibly kill a Python thread — if the code hangs,
    # the thread is abandoned after timeout but keeps running until the
    # process exits. In K8s, the pod-level timeout (commandTimeoutSec)
    # is the hard kill. This timeout is the soft "give up and report" layer.
    # ------------------------------------------------------------------
    exit_code = 0
    error_type = None

    def _exec():
        # Redirect stdout inside the thread so print() goes to our buffer.
        with contextlib.redirect_stdout(stdout_buf):
            exec(  # noqa: S102
                compile(code, "<agent_action>", "exec"),
                sandbox_globals,
            )

    # Do NOT use "with ThreadPoolExecutor() as pool:" here.
    # The context manager calls shutdown(wait=True) on exit, which would block
    # forever if the submitted thread is stuck in an infinite loop.
    # We call shutdown(wait=False) manually so we can return immediately on timeout.
    pool = ThreadPoolExecutor(max_workers=1)
    future = pool.submit(_exec)
    try:
        future.result(timeout=timeout_sec)

    except FuturesTimeout:
        exit_code = 1
        error_type = "TIMEOUT"
        stderr_buf.write(f"Execution timed out after {timeout_sec}s.\n")
        pool.shutdown(wait=False)  # abandon the stuck thread, don't block

    except Exception:
        # Any exception raised inside exec() surfaces here.
        # We capture the full traceback — this is what the agent reads
        # to understand what went wrong and self-correct on the next turn.
        exit_code = 1
        stderr_buf.write(traceback.format_exc())
        pool.shutdown(wait=False)

    # ------------------------------------------------------------------
    # Step 4: Return result
    # ------------------------------------------------------------------
    return ExecutionResult(
        exit_code=exit_code,
        stdout=stdout_buf.getvalue(),
        stderr=stderr_buf.getvalue(),
        elapsed_sec=round(time.monotonic() - start, 3),
        error_type=error_type,
    )
