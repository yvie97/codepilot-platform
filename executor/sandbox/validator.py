"""
Static analysis validator for agent-emitted code actions.

Before executing any code, we parse it as an AST (Abstract Syntax Tree) and
walk the tree looking for import statements. If the agent tries to import a
module outside the allowlist, we reject the code immediately — before a single
line runs.

Why AST instead of regex?
  A regex like r"import\s+os" can be defeated by:
    - "import   os" (extra spaces)
    - code that constructs the string "import socket" at runtime
  AST parsing is exact: it sees the actual structure of the code, not the text.
"""
import ast


# Modules the agent is allowed to import inside a code action.
# These are the modules needed by the tool functions and typical
# data-processing code. Everything else is blocked.
ALLOWED_MODULES = frozenset({
    "os",
    "subprocess",
    "pathlib",
    "json",
    "re",
    "shutil",
    "difflib",
    "textwrap",
    "xml",
    "xml.etree",
    "xml.etree.ElementTree",
    "collections",
    "itertools",
    "functools",
    "tempfile",
    "typing",
})


class SecurityError(Exception):
    """Raised when code violates the execution policy."""
    pass


def validate_imports(code: str) -> None:
    """
    Parse 'code' as an AST and verify all import statements are allowed.

    Raises:
        SyntaxError:   if the code is not valid Python
        SecurityError: if any import is outside ALLOWED_MODULES
    """
    # ast.parse raises SyntaxError if the code isn't valid Python.
    # We let that propagate — it will be caught by the runner and returned
    # as a stderr observation to the agent, which can then self-correct.
    tree = ast.parse(code)

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            # Handles: import os, import os.path
            for alias in node.names:
                _check_module(alias.name)

        elif isinstance(node, ast.ImportFrom):
            # Handles: from os import path, from pathlib import Path
            if node.module:
                _check_module(node.module)


def _check_module(module_name: str) -> None:
    """Check that the top-level package of module_name is allowed."""
    # "xml.etree.ElementTree" → root is "xml"
    root = module_name.split(".")[0]
    if root not in ALLOWED_MODULES:
        raise SecurityError(
            f"Import not allowed: '{module_name}'. "
            f"Use only the available tool functions instead of importing new modules."
        )
