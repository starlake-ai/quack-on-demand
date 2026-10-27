"""The tenant's authorized-location secrets (tenantStoreSql) reach DuckDB the way objectStoreSql
does: read from the secrets directory only, emitted for every kind, before any ATTACH, in both spawn
scripts and in the CLI's bundled copies."""

import pathlib
import re

REPO = pathlib.Path(__file__).resolve().parents[2]
SH = REPO / "scripts" / "spawn-quack-node.sh"
PS1 = REPO / "scripts" / "spawn-quack-node.ps1"


def test_sh_reads_it_as_a_secret_and_emits_it_after_the_database_secret_before_the_dispatch():
    body = SH.read_text()
    keys = body[body.index("SECRET_KEYS=("):]
    assert "tenantStoreSql" in keys[: keys.index(")")]
    use = body.index('${tenantStoreSql:-}')
    assert body.index('${objectStoreSql:-}') < use
    assert use < body.rindex('case "$kind" in')
    assert use < body.index('INIT_SQL+="ATTACH')


def test_ps1_reads_it_as_a_secret_and_appends_it_after_the_database_secret():
    body = PS1.read_text()
    assert re.search(r"\$tenantStoreSql\s*=\s*Secret-Or 'tenantStoreSql' ''", body)
    store = body.index("[void]$sb.AppendLine($objectStoreSql)")
    tenant = body.index("[void]$sb.AppendLine($tenantStoreSql)")
    assert store < tenant


def test_the_bundled_copies_are_current():
    bundled = REPO / "cli" / "src" / "qod_cli" / "scripts"
    assert (bundled / "spawn-quack-node.sh").read_text() == SH.read_text()
    assert (bundled / "spawn-quack-node.ps1").read_text() == PS1.read_text()
