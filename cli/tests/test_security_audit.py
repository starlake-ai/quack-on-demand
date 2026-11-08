import json

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
    assert json.loads(result.stdout) == body


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


def _answer(payload=None, content=None):
    if content is not None:
        return httpx.Response(200, content=content)
    return httpx.Response(200, json=payload)


@respx.mock
def test_json_with_a_critical_still_exits_three(runner):
    body = report("critical", "ok")
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=body))
    result = runner.invoke(app, ["--json", "admin", "security-audit"])
    assert result.exit_code == 3
    assert json.loads(result.stdout) == body


@respx.mock
def test_fail_on_given_twice(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=report("warn")))
    assert runner.invoke(app, ["admin", "security-audit", "--fail-on", "error", "--fail-on", "warn"]).exit_code == 3
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=report("error")))
    assert runner.invoke(app, ["admin", "security-audit", "--fail-on", "warn", "--fail-on", "error"]).exit_code == 3


@respx.mock
def test_fail_on_comma_separated(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=report("error")))
    assert runner.invoke(app, ["admin", "security-audit", "--fail-on", "warn, error"]).exit_code == 3
    assert runner.invoke(app, ["admin", "security-audit", "--fail-on", "warn,"]).exit_code == 0


@respx.mock
def test_invalid_fail_on_value(runner):
    route = respx.get(f"{BASE}/api/security/audit").mock(return_value=httpx.Response(200, json=report("ok")))
    result = runner.invoke(app, ["admin", "security-audit", "--fail-on", "critical"])
    assert result.exit_code == 1
    assert "--fail-on takes warn or error, not critical" in result.stderr
    assert not route.called


@respx.mock
def test_missing_findings(runner):
    body = report("ok")
    del body["findings"]
    respx.get(f"{BASE}/api/security/audit").mock(return_value=_answer(body))
    for args in (["admin", "security-audit"], ["--json", "admin", "security-audit"]):
        result = runner.invoke(app, args)
        assert result.exit_code == 1
        assert "error: unexpected answer from /api/security/audit (missing findings)" in result.stderr


@respx.mock
def test_missing_summary_in_human_mode(runner):
    body = report("ok")
    del body["summary"]
    respx.get(f"{BASE}/api/security/audit").mock(return_value=_answer(body))
    result = runner.invoke(app, ["admin", "security-audit"])
    assert result.exit_code == 1
    assert "(missing summary)" in result.stderr
    assert runner.invoke(app, ["--json", "admin", "security-audit"]).exit_code == 0


@respx.mock
def test_unknown_status_is_printed_and_does_not_fail(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=_answer(report("info")))
    result = runner.invoke(app, ["admin", "security-audit"])
    assert result.exit_code == 0, result.output
    assert "[info] title 0" in result.output
    assert "Traceback" not in result.output


@respx.mock
def test_unknown_status_beside_a_critical_still_fails(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=_answer(report("info", "critical")))
    assert runner.invoke(app, ["admin", "security-audit"]).exit_code == 3


@respx.mock
def test_empty_body(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=_answer(content=b""))
    result = runner.invoke(app, ["admin", "security-audit"])
    assert result.exit_code == 1
    assert "unexpected answer from /api/security/audit" in result.stderr


@respx.mock
def test_no_findings_prints_no_findings(runner):
    respx.get(f"{BASE}/api/security/audit").mock(return_value=_answer(report()))
    result = runner.invoke(app, ["admin", "security-audit"])
    assert result.exit_code == 0
    assert "no findings" in result.output


@respx.mock
def test_malformed_findings_entries(runner):
    not_an_object = report("ok")
    not_an_object["findings"].append("critical")
    bad_status = report("ok")
    bad_status["findings"][0]["status"] = 3
    for body in (not_an_object, bad_status):
        respx.get(f"{BASE}/api/security/audit").mock(return_value=_answer(body))
        for args in (["admin", "security-audit"], ["--json", "admin", "security-audit"]):
            result = runner.invoke(app, args)
            assert result.exit_code == 1, result.output
            assert "error: unexpected answer from /api/security/audit (findings entries)" in result.stderr
            assert "Traceback" not in result.output
