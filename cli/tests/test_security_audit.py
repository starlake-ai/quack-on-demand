import httpx
import respx

from qod_cli.main import app

BASE = "http://localhost:20900"


def report(*statuses):
    findings = [
        {
            "id": f"c{i}",
            "area": "network",
            "status": s,
            "title": f"title {i}",
            "detail": f"detail {i}",
            "fix": f"fix {i}",
            "settings": [],
            "docs": "https://docs.starlake.ai/qod/operating/tls",
        }
        for i, s in enumerate(statuses)
    ]
    summary = {k: sum(1 for s in statuses if s == k) for k in ("critical", "warn", "ok", "na", "error")}
    return {
        "generatedAt": "2026-11-08T10:00:00Z",
        "replica": "r1",
        "version": "v",
        "summary": summary,
        "findings": findings,
    }


@respx.mock
def test_clean_audit_exits_zero_and_prints_the_summary(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=report("ok", "warn")))
    result = runner.invoke(app, ["admin", "security-audit"])
    assert result.exit_code == 0, result.output
    assert "1 warning, 1 ok" in result.output
    assert "title 1" in result.output


@respx.mock
def test_a_critical_exits_three(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=report("critical")))
    assert runner.invoke(app, ["admin", "security-audit"]).exit_code == 3


@respx.mock
def test_fail_on_warn_and_error(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=report("warn", "error")))
    assert runner.invoke(app, ["admin", "security-audit"]).exit_code == 0
    assert runner.invoke(app, ["admin", "security-audit", "--fail-on", "warn"]).exit_code == 3
    assert runner.invoke(app, ["admin", "security-audit", "--fail-on", "error"]).exit_code == 3


@respx.mock
def test_json_prints_the_answer_as_is(runner):
    body = report("ok")
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=body))
    result = runner.invoke(app, ["--json", "admin", "security-audit"])
    assert result.exit_code == 0
    assert '"replica": "r1"' in result.output


@respx.mock
def test_not_a_superuser(runner):
    respx.get(f"{BASE}/api/security/audit").mock(
        return_value=httpx.Response(
            403,
            json={"error": "superuser_required", "message": "the security audit is restricted to superusers"},
        )
    )
    result = runner.invoke(app, ["admin", "security-audit"])
    assert result.exit_code == 1
    assert "restricted to superusers" in result.output
