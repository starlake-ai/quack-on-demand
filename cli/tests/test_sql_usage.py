import pytest

from qod_cli.main import app


def test_sql_without_user_is_usage_error(runner):
    result = runner.invoke(app, ["sql", "SELECT 1"])
    assert result.exit_code == 2
    assert "run qod login or set QOD_USER" in result.output


def test_sql_missing_adbc_driver_fails_with_platform_hint(monkeypatch, capsys):
    """No wheels exist for adbc-driver-flightsql on some platforms (Windows on
    ARM64), where pyproject markers skip the dependency: connecting must fail
    with a clear message, not an ImportError traceback."""
    import sys as _sys

    from qod_cli.config import Settings
    from qod_cli.sql import SqlClient

    monkeypatch.setitem(_sys.modules, "adbc_driver_flightsql", None)
    settings = Settings(sql_user="alice", sql_password="pw", tenant="acme", pool="bi")
    with pytest.raises(SystemExit):
        SqlClient.connect(settings)
    err = capsys.readouterr().err
    assert "adbc-driver-flightsql" in err
    assert "ARM64" in err
