from qod_cli.main import app


def test_sql_without_user_is_usage_error(runner):
    result = runner.invoke(app, ["sql", "SELECT 1"])
    assert result.exit_code == 2
    assert "run qod login or set QOD_USER" in result.output
