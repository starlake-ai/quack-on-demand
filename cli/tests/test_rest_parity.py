"""Parity gate: every REST operation in the generated OpenAPI document must be
reachable from the CLI with every parameter. xfail until the catch-up completes
(remove the marker in the final parity task; it must never return)."""

from pathlib import Path

import pytest
import yaml

import qod_cli.main  # noqa: F401  (imports all command modules -> fills REGISTRY)
from qod_cli.registry import REGISTRY
from tests._parity import compute_gaps, load_operations

# Browser-flow redirect targets, not operations a CLI can perform:
EXCLUSIONS = {
    "/api/auth/oidc/start",       # opens the provider login page in a browser (redirect dance)
    "/api/auth/oidc/callback",    # OIDC provider redirects the browser here
    "/api/auth/oidc/logout",      # browser session logout redirect
    "/api/auth/sql-token/start",  # opens the provider login page in a browser
    "/api/auth/sql-token/callback",  # provider redirect target
}

OPENAPI = Path(__file__).resolve().parent / "resources" / "openapi.yaml"


@pytest.mark.xfail(strict=False, reason="catch-up in progress; flips strict in the final parity task")
def test_cli_covers_every_rest_operation():
    assert OPENAPI.exists(), (
        f"{OPENAPI} missing; run "
        f"`sbt \"runMain ai.starlake.quack.docs.GenOpenApi cli/tests/resources/openapi.yaml <version>\"` "
        f"and commit"
    )
    spec = yaml.safe_load(OPENAPI.read_text())
    gaps = compute_gaps(load_operations(spec), REGISTRY, EXCLUSIONS)
    detail = "\n".join(
        [f"MISSING COMMAND: {m} {p}" for (m, p) in gaps.missing_operations]
        + [f"MISSING PARAMS: {m} {p}: {sorted(names)}"
           for (m, p), names in gaps.missing_params.items()]
    )
    assert gaps.empty(), f"CLI/REST parity gaps (OpenAPI {spec['info']['version']}):\n{detail}"
