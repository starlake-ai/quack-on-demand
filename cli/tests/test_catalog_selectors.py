from qod_cli.main import app


def test_as_of_and_tag_are_mutually_exclusive(runner):
    result = runner.invoke(
        app,
        ["catalog", "preview", "acme", "tpch1", "main", "orders", "--as-of", "1", "--as-of-tag", "v1"],
    )
    assert result.exit_code == 2
    assert "mutually exclusive" in result.output
