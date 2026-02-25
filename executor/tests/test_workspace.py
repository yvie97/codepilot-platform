"""
Tests for workspace/manager.py.

These tests never hit the network. Instead they use a local bare git
repository (created in a temp dir) as the clone source. This keeps the
tests fast, offline-friendly, and deterministic.

Run with:
    cd executor
    .venv/bin/pytest tests/test_workspace.py -v
"""
import subprocess
import pytest
from pathlib import Path

from workspace.manager import (
    create_workspace,
    delete_workspace,
    snapshot_workspace,
    restore_workspace,
    WORKSPACE_BASE,
    SNAPSHOTS_DIR,
)


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

def _run(cmd, cwd=None):
    subprocess.run(cmd, cwd=cwd, check=True, capture_output=True)


def _make_local_repo(base: Path) -> Path:
    """
    Create a minimal local git repository with one commit.
    Returns the repo path (used as clone source via 'file://' URL).
    """
    repo = base / "source-repo"
    repo.mkdir()
    _run(["git", "init"], cwd=repo)
    _run(["git", "config", "user.email", "test@test.com"], cwd=repo)
    _run(["git", "config", "user.name", "Test"], cwd=repo)
    (repo / "README.md").write_text("hello")
    _run(["git", "add", "."], cwd=repo)
    _run(["git", "commit", "-m", "init"], cwd=repo)
    return repo


# ------------------------------------------------------------------
# Fixtures
# ------------------------------------------------------------------

@pytest.fixture(autouse=True)
def patch_workspace_base(tmp_path, monkeypatch):
    """
    Redirect WORKSPACE_BASE and SNAPSHOTS_DIR to a temp directory for
    every test, so tests never touch /tmp/codepilot-workspaces on disk
    and never interfere with each other.
    """
    import workspace.manager as mgr
    monkeypatch.setattr(mgr, "WORKSPACE_BASE", tmp_path / "workspaces")
    monkeypatch.setattr(mgr, "SNAPSHOTS_DIR",  tmp_path / "workspaces" / "snapshots")
    (tmp_path / "workspaces").mkdir()


@pytest.fixture
def local_repo(tmp_path):
    """A local git repo that can be cloned via file:// URL."""
    return _make_local_repo(tmp_path)


@pytest.fixture
def existing_workspace(tmp_path, local_repo):
    """
    A workspace that has already been created (cloned from local_repo).
    Returns workspace_ref (str).
    """
    import workspace.manager as mgr
    ref = "job-fixture"
    create_workspace(ref, f"file://{local_repo}", git_ref="main")
    return ref


# ==================================================================
# create_workspace
# ==================================================================

class TestCreateWorkspace:

    def test_creates_directory(self, tmp_path, local_repo):
        import workspace.manager as mgr
        create_workspace("job-001", f"file://{local_repo}", git_ref="main")
        ws = mgr.WORKSPACE_BASE / "job-001"
        assert ws.is_dir()

    def test_cloned_files_are_present(self, tmp_path, local_repo):
        import workspace.manager as mgr
        create_workspace("job-002", f"file://{local_repo}", git_ref="main")
        ws = mgr.WORKSPACE_BASE / "job-002"
        assert (ws / "README.md").exists()

    def test_duplicate_ref_raises_file_exists_error(self, local_repo):
        create_workspace("job-dup", f"file://{local_repo}", git_ref="main")
        with pytest.raises(FileExistsError, match="already exists"):
            create_workspace("job-dup", f"file://{local_repo}", git_ref="main")

    def test_bad_url_raises_runtime_error(self):
        with pytest.raises(RuntimeError, match="git"):
            create_workspace("job-bad", "file:///nonexistent/repo", git_ref="main")

    def test_path_traversal_raises_value_error(self, local_repo):
        with pytest.raises(ValueError, match="traversal"):
            create_workspace("../escape", f"file://{local_repo}", git_ref="main")


# ==================================================================
# delete_workspace
# ==================================================================

class TestDeleteWorkspace:

    def test_deletes_directory(self, existing_workspace):
        import workspace.manager as mgr
        delete_workspace(existing_workspace)
        assert not (mgr.WORKSPACE_BASE / existing_workspace).exists()

    def test_missing_workspace_raises_file_not_found(self):
        with pytest.raises(FileNotFoundError, match="not found"):
            delete_workspace("ghost-job")

    def test_path_traversal_raises_value_error(self):
        with pytest.raises(ValueError, match="traversal"):
            delete_workspace("../../etc")


# ==================================================================
# snapshot_workspace
# ==================================================================

class TestSnapshotWorkspace:

    def test_returns_snapshot_key_and_size(self, existing_workspace):
        key, size = snapshot_workspace(existing_workspace)
        assert isinstance(key, str)
        assert existing_workspace in key   # key contains the workspace_ref
        assert size > 0

    def test_snapshot_file_exists_on_disk(self, existing_workspace):
        import workspace.manager as mgr
        key, _ = snapshot_workspace(existing_workspace)
        assert (mgr.SNAPSHOTS_DIR / f"{key}.tar.gz").exists()

    def test_multiple_snapshots_have_distinct_keys(self, existing_workspace):
        import time
        key1, _ = snapshot_workspace(existing_workspace)
        time.sleep(1.1)  # timestamp in key has 1-second resolution
        key2, _ = snapshot_workspace(existing_workspace)
        assert key1 != key2

    def test_missing_workspace_raises_file_not_found(self):
        with pytest.raises(FileNotFoundError):
            snapshot_workspace("ghost-job")


# ==================================================================
# restore_workspace
# ==================================================================

class TestRestoreWorkspace:

    def test_restore_removes_agent_changes(self, existing_workspace):
        import workspace.manager as mgr

        # Take a snapshot of the clean state.
        key, _ = snapshot_workspace(existing_workspace)

        # Simulate an agent writing a file.
        ws = mgr.WORKSPACE_BASE / existing_workspace
        damage = ws / "DAMAGE.txt"
        damage.write_text("broken")
        assert damage.exists()

        # Restore.
        restore_workspace(existing_workspace, key)

        # The damage file must be gone.
        assert not damage.exists()

    def test_restore_preserves_original_files(self, existing_workspace):
        import workspace.manager as mgr

        key, _ = snapshot_workspace(existing_workspace)

        # Delete a file (simulate agent deleting something).
        ws = mgr.WORKSPACE_BASE / existing_workspace
        readme = ws / "README.md"
        readme.unlink()
        assert not readme.exists()

        restore_workspace(existing_workspace, key)

        # README.md must be back.
        assert readme.exists()
        assert readme.read_text() == "hello"

    def test_missing_snapshot_raises_file_not_found(self, existing_workspace):
        with pytest.raises(FileNotFoundError, match="not found"):
            restore_workspace(existing_workspace, "nonexistent-key")

    def test_restore_works_even_if_workspace_deleted(self, existing_workspace):
        """Restore should recreate the workspace directory from scratch."""
        import workspace.manager as mgr

        key, _ = snapshot_workspace(existing_workspace)
        delete_workspace(existing_workspace)

        restore_workspace(existing_workspace, key)

        ws = mgr.WORKSPACE_BASE / existing_workspace
        assert ws.is_dir()
        assert (ws / "README.md").exists()
