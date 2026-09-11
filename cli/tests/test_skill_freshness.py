"""The wheel ships a copy of the Claude Code skill; the canonical file lives
in the repo's plugin dir (plugins/qod/skills/quack-on-demand/SKILL.md). Same
contract-copy pattern as tests/resources/openapi.yaml: the copy must be
byte-identical, and this test is the gate that keeps it fresh."""

from pathlib import Path

import pytest

PACKAGED = (
    Path(__file__).resolve().parents[1]
    / "src" / "qod_cli" / "skills" / "quack-on-demand" / "SKILL.md"
)
CANONICAL = (
    Path(__file__).resolve().parents[2]
    / "plugins" / "qod" / "skills" / "quack-on-demand" / "SKILL.md"
)


def test_packaged_skill_exists():
    assert PACKAGED.is_file(), f"{PACKAGED} missing from the package"


def test_packaged_skill_matches_plugin_copy():
    if not CANONICAL.exists():
        pytest.skip("repo checkout not present (running from an sdist)")
    assert PACKAGED.read_bytes() == CANONICAL.read_bytes(), (
        "packaged skill is stale; refresh it with:\n"
        "  cp plugins/qod/skills/quack-on-demand/SKILL.md "
        "cli/src/qod_cli/skills/quack-on-demand/SKILL.md"
    )
