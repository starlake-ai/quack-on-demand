import re
import sys
from pathlib import Path

import typer

from ..sql import SqlClient, render_table


def split_statements(text: str) -> list[tuple[int, str]]:
    """Split a SQL script into (start_line, statement) pairs on top-level
    semicolons, respecting single-quoted strings ('' escape), double-quoted
    identifiers, line comments, nested block comments (DuckDB nests them),
    and dollar-quoted strings ($$...$$ and $tag$...$tag$). Statements that
    are empty or comment/whitespace-only are dropped."""
    out: list[tuple[int, str]] = []
    n = len(text)
    i = 0
    line = 1
    start = 0
    start_line = 1
    pending = True  # start_line snaps to the first non-whitespace char

    def flush(end: int) -> None:
        stmt = text[start:end].strip()
        # Drop chunks with no content outside comments (a trailing comment
        # block after the last ; would otherwise become a bogus statement).
        if stmt and not _comment_only(stmt):
            out.append((start_line, stmt))

    while i < n:
        c = text[i]
        if pending and not c.isspace():
            start_line = line
            pending = False
        if c == "\n":
            line += 1
            i += 1
        elif c == "'":
            i += 1
            while i < n:
                if text[i] == "\n":
                    line += 1
                if text[i] == "'":
                    if i + 1 < n and text[i + 1] == "'":
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
        elif c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\n":
                    line += 1
                i += 1
            i += 1
        elif c == "-" and i + 1 < n and text[i + 1] == "-":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if text[i] == "\n":
                    line += 1
                    i += 1
                elif text[i] == "/" and i + 1 < n and text[i + 1] == "*":
                    depth += 1
                    i += 2
                elif text[i] == "*" and i + 1 < n and text[i + 1] == "/":
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif c == "$":
            m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*)?\$", text[i:])
            if m:
                tag = m.group(0)
                close = text.find(tag, i + len(tag))
                if close == -1:
                    i = n  # unterminated: swallow to EOF, statement fails server-side
                else:
                    line += text.count("\n", i, close + len(tag))
                    i = close + len(tag)
            else:
                i += 1
        elif c == ";":
            flush(i)
            i += 1
            start = i
            pending = True
        else:
            i += 1
    flush(n)
    return out


def _comment_only(stmt: str) -> bool:
    """True when nothing survives outside comments. Good enough for the
    drop-empty-chunk case: comment markers inside string literals cannot
    reach here as the WHOLE remaining text, because a statement containing
    a string literal also contains non-comment content around it."""
    return not re.sub(r"--[^\n]*|/\*.*?\*/", "", stmt, flags=re.S).strip()


def run_file(client, path: Path, mode: str) -> None:
    """Execute a SQL script sequentially, failing fast: the first statement
    error aborts with exit 1, naming the statement and its line."""
    text = sys.stdin.read() if str(path) == "-" else path.read_text()
    statements = split_statements(text)
    if not statements:
        typer.echo("no statements found", err=True)
        raise typer.Exit(1)
    for idx, (start_line, stmt) in enumerate(statements, 1):
        try:
            render_table(client.query(stmt), mode)
        except Exception as exc:
            typer.echo(
                f"error in statement {idx} (line {start_line}): {exc}", err=True
            )
            raise typer.Exit(1)


def repl(client, mode: str, input_fn=input) -> None:
    try:
        import readline  # noqa: F401  (line editing + history; pyreadline3 provides it on Windows)
    except ImportError:
        pass
    import sys

    buffer: list[str] = []
    while True:
        prompt = "qod> " if not buffer else "...> "
        try:
            line = input_fn(prompt)
        except EOFError:
            break
        except KeyboardInterrupt:
            buffer.clear()
            print(file=sys.stderr)
            continue
        if not buffer and line.strip() == "\\q":
            break
        buffer.append(line)
        if not line.rstrip().endswith(";"):
            continue
        statement = "\n".join(buffer)
        buffer.clear()
        try:
            render_table(client.query(statement), mode)
        except Exception as exc:
            print(f"error: {exc}", file=sys.stderr)


def _mode(ctx: typer.Context, csv_flag: bool) -> str:
    if csv_flag and ctx.obj.json_output:
        raise typer.BadParameter("--csv and --json are mutually exclusive")
    return "csv" if csv_flag else "json" if ctx.obj.json_output else "table"


def _connect(ctx: typer.Context) -> SqlClient:
    settings = ctx.obj.settings
    if not settings.sql_user:
        raise typer.BadParameter("no SQL user configured; run qod login or set QOD_USER")
    if not settings.sql_password:
        settings.sql_password = typer.prompt(f"Password for {settings.sql_user}", hide_input=True)
    return SqlClient.connect(settings)


def sql(
    ctx: typer.Context,
    statement: str = typer.Argument(None, help="SQL to run; omit for the interactive REPL."),
    file: Path = typer.Option(
        None,
        "--file",
        "-f",
        help="Run a SQL script: statements split on top-level ';', executed "
        "in order, first error aborts (exit 1). '-' reads stdin.",
    ),
    csv_flag: bool = typer.Option(False, "--csv", help="CSV to stdout."),
    tenant: str = typer.Option(None, "--tenant", help="Override the profile tenant."),
    pool: str = typer.Option(None, "--pool", help="Override the profile pool."),
    superuser: bool = typer.Option(None, "--superuser/--no-superuser"),
):
    """Run SQL against the FlightSQL edge."""
    settings = ctx.obj.settings
    if tenant is not None:
        settings.tenant = tenant
    if pool is not None:
        settings.pool = pool
    if superuser is not None:
        settings.superuser = superuser
    mode = _mode(ctx, csv_flag)
    if file is not None and statement is not None:
        raise typer.BadParameter("pass either an inline statement or --file, not both")
    if file is not None:
        if str(file) != "-" and not file.exists():
            raise typer.BadParameter(f"no such file: {file}")
        try:
            with _connect(ctx) as client:
                run_file(client, file, mode)
        except (typer.BadParameter, typer.Exit):
            raise
        except Exception as exc:
            typer.echo(f"error: {exc}", err=True)
            raise typer.Exit(1)
        return
    if statement is None:
        try:
            with _connect(ctx) as client:
                repl(client, mode)
        except (typer.BadParameter, typer.Exit):
            raise
        except Exception as exc:
            typer.echo(f"error: {exc}", err=True)
            raise typer.Exit(1)
        return
    try:
        with _connect(ctx) as client:
            render_table(client.query(statement), mode)
    except (typer.BadParameter, typer.Exit):
        raise
    except Exception as exc:  # ADBC raises driver-specific exception types
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)
