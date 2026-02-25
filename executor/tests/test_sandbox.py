"""
Tests for sandbox/validator.py and sandbox/runner.py.

Run with:
    cd executor
    .venv/bin/pytest tests/test_sandbox.py -v
"""
import pytest
from pathlib import Path

from sandbox.validator import validate_imports, SecurityError
from sandbox.runner import run_code


# ------------------------------------------------------------------
# Fixtures
# ------------------------------------------------------------------

@pytest.fixture
def workspace(tmp_path):
    """A temporary directory that acts as the sandbox workspace."""
    return tmp_path


# ==================================================================
# validator.py tests
# ==================================================================

class TestValidateImports:

    def test_allowed_import_passes(self):
        validate_imports("import os")

    def test_allowed_from_import_passes(self):
        validate_imports("from pathlib import Path")

    def test_allowed_submodule_passes(self):
        validate_imports("from xml.etree.ElementTree import parse")

    def test_blocked_import_raises_security_error(self):
        with pytest.raises(SecurityError, match="socket"):
            validate_imports("import socket")

    def test_blocked_from_import_raises_security_error(self):
        with pytest.raises(SecurityError, match="requests"):
            validate_imports("from requests import get")

    def test_blocked_network_module(self):
        with pytest.raises(SecurityError):
            validate_imports("import urllib.request")

    def test_syntax_error_propagates(self):
        with pytest.raises(SyntaxError):
            validate_imports("def broken(:")

    def test_no_imports_passes(self):
        validate_imports("x = 1 + 1\nprint(x)")

    def test_multiple_imports_one_blocked(self):
        # Even if some imports are allowed, one blocked import rejects the whole block.
        with pytest.raises(SecurityError):
            validate_imports("import os\nimport socket")


# ==================================================================
# runner.py tests
# ==================================================================

class TestRunCode:

    # --- Happy path ---

    def test_normal_code_returns_exit_zero(self, workspace):
        result = run_code("print('hello')", workspace)
        assert result.exit_code == 0
        assert result.stdout.strip() == "hello"
        assert result.stderr == ""
        assert result.error_type is None

    def test_stdout_is_captured(self, workspace):
        result = run_code("for i in range(3):\n    print(i)", workspace)
        assert result.exit_code == 0
        assert result.stdout.strip() == "0\n1\n2"

    def test_elapsed_sec_is_non_negative(self, workspace):
        result = run_code("x = 1", workspace)
        assert result.elapsed_sec >= 0

    # --- Sandbox tool injection ---

    def test_write_and_read_file_tool(self, workspace):
        code = (
            "write_file('hello.txt', 'world')\n"
            "content = read_file('hello.txt')\n"
            "print(content)"
        )
        result = run_code(code, workspace)
        assert result.exit_code == 0
        assert result.stdout.strip() == "world"

    def test_list_files_tool(self, workspace):
        (workspace / "a.py").write_text("x=1")
        (workspace / "b.py").write_text("y=2")
        result = run_code("print(sorted(list_files('.', '*.py')))", workspace)
        assert result.exit_code == 0
        assert "a.py" in result.stdout
        assert "b.py" in result.stdout

    # --- Policy violations ---

    def test_blocked_import_returns_policy_violation(self, workspace):
        result = run_code("import socket", workspace)
        assert result.exit_code == 1
        assert result.error_type == "POLICY_VIOLATION"
        assert result.stdout == ""

    def test_exec_builtin_is_blocked(self, workspace):
        # exec() is stripped from builtins — calling it should raise NameError
        result = run_code("exec('print(1)')", workspace)
        assert result.exit_code == 1
        assert "NameError" in result.stderr or "POLICY_VIOLATION" in (result.error_type or "")

    def test_open_outside_workspace_raises_permission_error(self, workspace):
        result = run_code("open('/etc/passwd')", workspace)
        assert result.exit_code == 1
        assert "PermissionError" in result.stderr

    # --- Runtime errors ---

    def test_exception_in_code_is_captured(self, workspace):
        result = run_code("raise ValueError('oops')", workspace)
        assert result.exit_code == 1
        assert "ValueError" in result.stderr
        assert "oops" in result.stderr
        assert result.error_type is None  # not a policy violation, just a crash

    def test_name_error_is_captured(self, workspace):
        result = run_code("undefined_var", workspace)
        assert result.exit_code == 1
        assert "NameError" in result.stderr

    # --- Timeout ---

    def test_infinite_loop_times_out(self, workspace):
        result = run_code("while True: pass", workspace, timeout_sec=1)
        assert result.exit_code == 1
        assert result.error_type == "TIMEOUT"
        assert "timed out" in result.stderr

    def test_timeout_returns_quickly(self, workspace):
        """The server must return within ~timeout+1 seconds, not hang."""
        import time
        start = time.monotonic()
        run_code("while True: pass", workspace, timeout_sec=1)
        elapsed = time.monotonic() - start
        assert elapsed < 3, f"Timed-out code blocked for {elapsed:.1f}s"

    # --- Syntax errors ---

    def test_syntax_error_returns_exit_one(self, workspace):
        result = run_code("def broken(:", workspace)
        assert result.exit_code == 1
        assert result.error_type == "SYNTAX_ERROR"
        assert "SyntaxError" in result.stderr
