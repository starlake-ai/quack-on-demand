"""Parity gate: every REST operation in the generated OpenAPI document must be
reachable from the CLI with every parameter. Gate is strict; add exclusions
only with justification."""

from pathlib import Path

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
    # Starlake SSO handoff: the manager mints a one-time ticket for the browser to
    # carry to Starlake, which redeems it server-to-server. Neither leg is a CLI
    # operation (no human at a terminal holds a ticket).
    "/api/auth/sso/ticket",
    "/api/auth/sso/redeem",
    # Admin-UI support read: feeds the database-create form's "QoD default
    # Postgres" section. The CLI has no create-form to prefill, and the same
    # values are operator config (QOD_PG_*), so no CLI command is warranted.
    "/api/database/metastore-defaults",
    # SCIM 2.0 provisioning surface: driven machine-to-machine by an identity
    # provider's connector (Okta, Entra, Google), speaking the RFC 7644 wire
    # format with Authorization: Bearer. Humans manage users/groups through the
    # existing user/group/membership CLI commands, so no CLI surface is warranted.
    "/api/scim/v2/{tenant}/Users",
    "/api/scim/v2/{tenant}/Users/{id}",
    "/api/scim/v2/{tenant}/Groups",
    "/api/scim/v2/{tenant}/Groups/{id}",
    "/api/scim/v2/{tenant}/ServiceProviderConfig",
    "/api/scim/v2/{tenant}/ResourceTypes",
    "/api/scim/v2/{tenant}/Schemas",
}

OPENAPI = Path(__file__).resolve().parent / "resources" / "openapi.yaml"


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
