"""
Tool functions injected into the agent's sandbox execution context.

These are the functions an agent can call inside a code action (§5.2).
They are plain Python callables built as closures that capture
'workspace_dir' — so every file operation is automatically scoped
to the correct workspace without the agent needing to know the path.

Usage (from the sandbox runner):
    tools = build_tools(workspace_dir)
    exec(agent_code, {"__builtins__": ..., **tools})

The agent then writes code like:
    content = read_file("src/main/java/Foo.java")
    apply_patch(diff)
    result = run_command(["mvn", "-q", "test"])
    print(result["stdout"])
"""
import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

# Commands the agent is allowed to run via run_command().
# This list is intentionally short — only what's needed for Java build/test.
ALLOWED_COMMANDS = frozenset({"mvn", "./gradlew", "java", "git", "rg"})


def build_tools(workspace_dir: Path) -> dict[str, Any]:
    """
    Build and return the tool function dict for a given workspace.

    Every function here is a closure over 'workspace_dir', so the agent
    can write relative paths like "src/main/java/Foo.java" and the tool
    will automatically resolve them to the correct absolute path.
    """
    ws = workspace_dir.resolve()

    # ------------------------------------------------------------------
    # Path safety helper
    # ------------------------------------------------------------------

    def _safe_path(relative: str) -> Path:
        """
        Resolve a relative path inside the workspace.
        Raises PermissionError if the path escapes the workspace root
        (path traversal attack prevention).
        """
        resolved = (ws / relative).resolve()
        if not str(resolved).startswith(str(ws)):
            raise PermissionError(
                f"Path '{relative}' resolves outside workspace. "
                "Path traversal is not allowed."
            )
        return resolved

    # ------------------------------------------------------------------
    # File operations
    # ------------------------------------------------------------------

    def read_file(path: str) -> str:
        """Read a file from the workspace. 'path' is relative to workspace root."""
        return _safe_path(path).read_text(encoding="utf-8")

    def write_file(path: str, content: str) -> None:
        """Write 'content' to a file in the workspace (creates parent dirs)."""
        target = _safe_path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def list_files(path: str = ".", pattern: str = "**/*") -> list[str]:
        """
        List files matching 'pattern' under 'path'.
        Returns paths relative to workspace root.
        """
        base = _safe_path(path)
        return [
            str(p.relative_to(ws))
            for p in base.glob(pattern)
            if p.is_file()
        ]

    def search_code(pattern: str, path: str = ".") -> list[dict]:
        """
        Search for a regex pattern in files using ripgrep (rg).
        Returns a list of {file, line, text} matches.

        Falls back to an empty list if rg is not installed.
        """
        base = _safe_path(path)
        try:
            result = subprocess.run(
                ["rg", "--json", pattern, str(base)],
                capture_output=True, text=True, timeout=30
            )
        except FileNotFoundError:
            # rg not installed — return empty rather than crashing
            return []

        matches = []
        for line in result.stdout.splitlines():
            try:
                data = json.loads(line)
                if data.get("type") == "match":
                    matches.append({
                        "file": data["data"]["path"]["text"],
                        "line": data["data"]["line_number"],
                        "text": data["data"]["lines"]["text"].strip(),
                    })
            except (json.JSONDecodeError, KeyError):
                pass
        return matches

    # ------------------------------------------------------------------
    # Git operations
    # ------------------------------------------------------------------

    def git_status() -> str:
        """Return the output of 'git status' in the workspace."""
        r = subprocess.run(
            ["git", "status"], cwd=ws,
            capture_output=True, text=True, timeout=15
        )
        return r.stdout

    def git_diff(base: str = "HEAD") -> str:
        """Return the unified diff of working tree vs 'base' (default HEAD)."""
        r = subprocess.run(
            ["git", "diff", base], cwd=ws,
            capture_output=True, text=True, timeout=15
        )
        return r.stdout

    def apply_patch(diff: str) -> dict:
        """
        Apply a unified diff string to the workspace using 'git apply'.

        Returns a dict with keys: exit_code, stdout, stderr, success.
        The agent should check 'success' and read 'stderr' on failure
        to understand what went wrong and self-correct.
        """
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".patch", delete=False, encoding="utf-8"
        ) as f:
            f.write(diff)
            patch_path = f.name

        try:
            r = subprocess.run(
                ["git", "apply", "--whitespace=fix", patch_path],
                cwd=ws, capture_output=True, text=True, timeout=30
            )
            return {
                "exit_code": r.returncode,
                "stdout":    r.stdout,
                "stderr":    r.stderr,
                "success":   r.returncode == 0,
            }
        finally:
            os.unlink(patch_path)

    def git_reset(to_ref: str = "HEAD") -> None:
        """Hard-reset the workspace to 'to_ref' (discards all uncommitted changes)."""
        subprocess.run(
            ["git", "reset", "--hard", to_ref],
            cwd=ws, check=True, timeout=15
        )

    # ------------------------------------------------------------------
    # Build / test
    # ------------------------------------------------------------------

    def run_command(cmd: list[str], timeout: int = 300) -> dict:
        """
        Run an allowlisted command in the workspace directory.

        Only commands in ALLOWED_COMMANDS are permitted. Any other command
        raises PermissionError, which surfaces as a POLICY_VIOLATION in the
        ExecutionResult (§8.5 failure table).

        Returns a dict with keys: exit_code, stdout, stderr.
        """
        if not cmd:
            raise ValueError("cmd list cannot be empty")

        executable = cmd[0]
        if executable not in ALLOWED_COMMANDS:
            raise PermissionError(
                f"Command not allowed: '{executable}'. "
                f"Allowed commands: {sorted(ALLOWED_COMMANDS)}"
            )

        r = subprocess.run(
            cmd, cwd=ws,
            capture_output=True, text=True,
            timeout=timeout
        )
        return {
            "exit_code": r.returncode,
            "stdout":    r.stdout,
            "stderr":    r.stderr,
        }

    # ------------------------------------------------------------------
    # Return all tools as a dict — this is what gets merged into exec() globals
    # ------------------------------------------------------------------

    return {
        "read_file":   read_file,
        "write_file":  write_file,
        "list_files":  list_files,
        "search_code": search_code,
        "git_status":  git_status,
        "git_diff":    git_diff,
        "apply_patch": apply_patch,
        "git_reset":   git_reset,
        "run_command": run_command,
    }
