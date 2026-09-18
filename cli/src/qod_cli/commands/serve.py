"""`qod serve <target>`: one command from your own data to a queryable gateway.

Boots a manager on a PERSISTENT embedded Postgres (QOD_PG_EMBEDDED, see
ai.starlake.quack.boot.EmbeddedControlPlane) so there is no external prerequisite,
then provisions a tenant, database, and pool around whatever the target points at
and prints the client connection strings.

Without `--demo` this is not the demo: `qod serve --demo` (and its deprecated alias
`qod start --demo`) is ephemeral, seeded with TPC-H, and deliberately insecure (its
posture comes from DemoConfig.overlay and lives only on that code path); the default
`qod serve` path is persistent and keeps the normal secure posture: TLS on, DB auth
on, ACL on, a generated admin password instead of 'admin'.

Provisioning runs in a thread beside the output relay because the manager has to
be up before the REST calls can land, and `_run_supervised` owns the foreground
for the JVM's lifetime. Every provisioning step is ensure-semantics, so a failed
or interrupted run is resumed by simply re-running the command.
"""

from __future__ import annotations

import os
import secrets
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import httpx
import typer

from .. import launcher
from ..config import Settings, load_start_env, save_profile, save_start_env
from ..rest import ApiError, RestClient
from ..serve_provision import (
    ProvisionError,
    ensure_database,
    ensure_pool,
    ensure_tenant,
    wait_node_routable,
    wait_ready,
)
from ..serve_target import TargetError, composed_db_name
from ..serve_target import resolve as resolve_target
from ._launch import _exec, resolve_jar, resolve_java
from .demo import run_demo

# The seeded superuser. QOD_ADMIN_USERNAME defaults to "admin@localhost.local,admin",
# so both names exist; the short one is what a person types.
_ADMIN_USER = "admin"


def _resolve_admin_password() -> tuple[str, bool]:
    """(password, generated_now).

    Precedence is the CLI's usual one: a real QOD_ADMIN_PASSWORD wins, then the
    value a previous `qod serve` (or `qod setup`) stored, and only a completely
    fresh install generates. Reusing the stored value is what makes the password
    printed on the first serve keep working on every later one.
    """
    from_env = os.environ.get("QOD_ADMIN_PASSWORD")
    if from_env:
        return from_env, False
    stored = load_start_env().get("QOD_ADMIN_PASSWORD")
    if stored:
        return stored, False
    return secrets.token_urlsafe(12), True


def _manager_running(manager_url: str) -> bool:
    """True when something already answers GET /ready at MANAGER_URL - attach
    to it instead of booting a second JVM. Mirrors status.py's `_get_json`
    posture: any exception (connection refused, timeout, DNS) means nothing is
    there yet, not a reason to abort serve. The response's status code does not
    matter here (a 503 mid-boot still means "something is listening").

    `httpx.Timeout(2.0, connect=1.0)` bounds every phase (connect/read/write/
    pool) individually rather than the call overall - a bare `timeout=2.0`
    does the same thing, but spelling it out makes the per-phase nature
    explicit rather than implying a 2s wall-clock cap."""
    try:
        httpx.get(f"{manager_url}/ready", timeout=httpx.Timeout(2.0, connect=1.0))
        return True
    except Exception:
        return False


_LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "::1"}


def _is_loopback(manager_url: str) -> bool:
    return (urlparse(manager_url).hostname or "").lower() in _LOOPBACK_HOSTS


def _scheme_family(target: str | None) -> str | None:
    """s3 | gs | az | None, read off the raw TARGET string before serve_target.resolve()
    runs (so a "gcs://" alias is still literally "gcs://" here; it maps to the same
    "gs" family as "gs://"). None covers local, bare, and glob targets - not a URI at
    all - which keep the s3-flavored default below since any credentials there only
    ever back a remote --table view, not the target's own dataPath."""
    low = (target or "").lower()
    if low.startswith(("s3://", "s3a://", "r2://")):
        return "s3"
    if low.startswith(("gs://", "gcs://")):
        return "gs"
    if low.startswith(("az://", "azure://", "abfss://")):
        return "az"
    return None


def _warn_if_no_credentials(out: dict, scheme: str) -> None:
    if not out:
        typer.echo(
            f"note: no object-store credentials given for {scheme}; relying on public "
            "access or the engine's ambient credential chain",
            err=True,
        )


def _object_store(
    scheme_family: str | None, access_key_id: str | None, secret_access_key: str | None,
    region: str | None, endpoint: str | None,
) -> dict:
    """The credential vocabulary ObjectStoreSecret.sql dispatches on, keyed by the
    TARGET's scheme (SCHEME_FAMILY, from _scheme_family) - not by which flags were
    passed: s3/s3a/r2 -> s3_*, gs (gcs is an alias, normalized in serve_target) ->
    gcs_hmac_key_id/gcs_hmac_secret, az/azure/abfss -> azure_account/azure_account_key.
    A local or bare target (scheme_family=None) keeps the s3 vocabulary, since any
    credentials there only ever back a remote --table view. Flags win; only s3 falls
    back to the ambient AWS_* environment (gs/az have no equivalent convention).
    --region/--endpoint only mean anything for s3 (the gcs/azure secrets carry
    neither field), so they are refused for gs/az rather than silently dropped."""
    if scheme_family == "gs":
        if region or endpoint:
            raise TargetError(
                "--region/--endpoint do not apply to a gs:// target: the gcs secret "
                "DuckDB creates has no region or endpoint field."
            )
        out: dict = {}
        if access_key_id or secret_access_key:
            if not (access_key_id and secret_access_key):
                raise TargetError(
                    "a gs:// target needs BOTH --access-key-id (the HMAC key id) and "
                    "--secret-access-key (the HMAC secret) to build the gcs secret "
                    "DuckDB creates; only one was given."
                )
            out["gcs_hmac_key_id"] = access_key_id
            out["gcs_hmac_secret"] = secret_access_key
        _warn_if_no_credentials(out, "gs")
        return out

    if scheme_family == "az":
        if region or endpoint:
            raise TargetError(
                "--region/--endpoint do not apply to an az:// target: the azure secret "
                "DuckDB creates has no region or endpoint field."
            )
        out = {}
        if access_key_id or secret_access_key:
            if not (access_key_id and secret_access_key):
                raise TargetError(
                    "an az:// target needs BOTH --access-key-id (the storage account "
                    "name) and --secret-access-key (the account key) to build the azure "
                    "secret DuckDB creates; only one was given."
                )
            out["azure_account"] = access_key_id
            out["azure_account_key"] = secret_access_key
        _warn_if_no_credentials(out, "az")
        return out

    # s3/s3a/r2, and local/bare/glob targets: today's behavior, unchanged.
    out = {}
    key = access_key_id or os.environ.get("AWS_ACCESS_KEY_ID")
    secret = secret_access_key or os.environ.get("AWS_SECRET_ACCESS_KEY")
    reg = region or os.environ.get("AWS_REGION") or os.environ.get("AWS_DEFAULT_REGION")
    if key:
        out["s3_access_key_id"] = key
    if secret:
        out["s3_secret_access_key"] = secret
    if reg:
        out["s3_region"] = reg
    if endpoint:
        out["s3_endpoint"] = endpoint
    if not key and not secret:
        # A region/endpoint-only map would still produce a scoped CREATE SECRET
        # server-side with KEY_ID ''/SECRET '' (ObjectStoreSecret.s3Secret defaults
        # a missing key to ""), which OUTRANKS DuckDB's ambient credential chain for
        # exactly the served prefix - the empty-credential trap this whole scheme
        # dispatch exists to prevent. Drop region/endpoint entirely rather than ship
        # a secret with no actual credentials.
        out = {}
    if scheme_family == "s3":
        _warn_if_no_credentials(out, "s3")
    return out


def _credential_tail_note(target: str | None, tables: list[str]) -> str | None:
    """One stderr note when a --table view's glob lives under a different
    object-store scheme family than the anchor (including a local/bare anchor
    with remote --table globs). _object_store scopes its CREATE SECRET to the
    anchor's own family, so a mismatched view gets no secret of its own and
    falls back to the engine's ambient credential chain - worth flagging, not
    worth refusing over."""
    anchor_family = _scheme_family(target)
    mismatched: set[str] = set()
    for item in tables:
        _, _, glob = item.partition("=")
        family = _scheme_family(glob.strip())
        if family is not None and family != anchor_family:
            mismatched.add(family)
    if not mismatched:
        return None
    return (
        f"note: --table view(s) use a different object-store scheme than the anchor "
        f"(anchor: {anchor_family or 'local'}, --table: {', '.join(sorted(mismatched))}); "
        "those views get no scoped secret and rely on the engine's ambient credential chain"
    )


def _banner(
    *, tenant: str, db: str, pool: str, size: int, password: str, generated: bool,
    edge_host: str, edge_port: int, manager_url: str, pg_port: int, pg_data_dir: str,
    description: str, attached: bool = False,
) -> str:
    """The connect snippet. The PLAINTEXT password appears only on the run that
    generated it: reprinting a stored secret on every boot would put it in every
    terminal scrollback and CI log for no benefit. The "stored in <config path>"
    line is unconditional (generated or not), so a JVM death before the generating
    run's banner still leaves the user a way back in.

    The connect lines (JDBC/ADBC/ODBC/UI) and the one-time plaintext password
    line are styled (bold, colored) so they stand out for copy-pasting; click
    auto-strips the ANSI codes when the destination isn't a terminal, so piped
    output and CI logs stay plain text.

    ATTACHED (B1, #100): this run provisioned into an already-running manager
    rather than booting its own. The embedded-postgres lines make no sense there
    - that manager's control plane might be external and this process never
    touched it - so the header and tail change; everything else (credentials,
    connect strings) is identical either way."""
    from ..config import config_path

    # The seeded admin is a SUPERUSER row (tenant IS NULL): the FlightSQL edge picks
    # the auth realm off a "superuser" header (JDBC/ADBC/ODBC params become gRPC
    # headers), and with tenant= present but no superuser flag it auths in the
    # TENANT realm instead, where no admin row exists - "Invalid password" for the
    # exact string this banner just handed the user. Only the seeded admin needs
    # it; tenant users created later connect without it (see the add-a-user hint
    # below).
    jdbc = (
        f"jdbc:arrow-flight-sql://{edge_host}:{edge_port}/"
        f"?tenant={tenant}&pool={pool}&user={_ADMIN_USER}"
        "&useEncryption=true&disableCertificateVerification=true&superuser=true"
    )
    # ADBC/ODBC mirror the manager's own boot box (Banner.scala), but with the
    # real tenant/pool/user filled in instead of <placeholder>s. The password
    # itself stays out of both: ADBC keeps the bare "password" keyword and ODBC
    # keeps the literal "<password>" placeholder, matching the JDBC/password
    # rule above - the plaintext must appear at most once per credential
    # lifetime, not on every boot's connect strings.
    adbc = (
        f"uri=grpc+tls://{edge_host}:{edge_port}  (adbc_driver_flightsql; "
        f"db_kwargs: username={_ADMIN_USER}, password, plus grpc headers "
        f"tenant={tenant}, pool={pool}, superuser=true)"
    )
    odbc = (
        "Driver={Arrow Flight SQL ODBC Driver};"
        f"Host={edge_host};Port={edge_port};UseEncryption=true;"
        f"DisableCertificateVerification=true;UID={_ADMIN_USER};PWD=<password>;"
        f"SUPERUSER=true;TENANT={tenant};POOL={pool}"
    )
    # The manager's own boot box ends with a bare "====" ruler; this title makes
    # the provisioning summary below it a labeled section instead of loose lines.
    ruler = "=" * 78
    if attached:
        lines = [
            "",
            ruler,
            " qod serve: provisioned into the running gateway",
            ruler,
            f"  manager       : {manager_url}",
            f"  serving       : {description}",
            f"  tenant/db/pool: {tenant} / {db} / {pool}  ({size} dual node)",
            typer.style(f"  admin user    : {_ADMIN_USER}", fg=typer.colors.YELLOW),
        ]
    else:
        lines = [
            "",
            ruler,
            " qod serve: your gateway is ready",
            ruler,
            f"  control plane : embedded postgres on localhost:{pg_port}",
            f"  pg data       : {pg_data_dir}/pgdata",
            "                  relocate with --pg-data-dir <dir> or QOD_PG_EMBEDDED_DATA_DIR",
            "                  (a new dir starts a fresh control plane; move the old dir to keep "
            "your tenants)",
            f"  serving       : {description}",
            f"  tenant/db/pool: {tenant} / {db} / {pool}  ({size} dual node)",
            typer.style(f"  admin user    : {_ADMIN_USER}", fg=typer.colors.YELLOW),
        ]
    if generated:
        # Bold+yellow so the one-time plaintext doesn't blend into the rest of the
        # scrollback. typer.style() only wraps the whole string (ANSI codes go at
        # the very start and end, never mid-string), so the literal
        # "password      : <value>" text stays contiguous for anything matching on
        # it, and click.echo strips the codes automatically when stdout/stderr
        # isn't a terminal (piped output, CI logs stay plain).
        lines.append(
            typer.style(
                f"  password      : {password}   (generated, shown once)",
                fg=typer.colors.YELLOW,
                bold=True,
            )
        )
        lines.append(
            typer.style(
                f"                  stored as QOD_ADMIN_PASSWORD ([start] table) in {config_path()}",
                fg=typer.colors.YELLOW,
            )
        )
    else:
        # No preceding "password :" line to hang off of here, so this is its own
        # aligned row rather than a continuation.
        lines.append(
            typer.style(
                f"  password      : stored as QOD_ADMIN_PASSWORD ([start] table) in {config_path()}",
                fg=typer.colors.YELLOW,
            )
        )
    lines.append(
        typer.style(
            f"  rotate password: qod auth change-password --username {_ADMIN_USER}",
            fg=typer.colors.YELLOW,
        )
    )
    lines += [
        "",
        typer.style(f"  JDBC {jdbc}", fg="cyan", bold=True),
        typer.style(f"  ADBC {adbc}", fg="cyan", bold=True),
        typer.style(f"  ODBC {odbc}", fg="cyan", bold=True),
        typer.style(f"  UI   {manager_url.rstrip('/')}/ui/", fg="cyan", bold=True),
        "",
        "  gateway already running; stop it with: qod stop" if attached else "  Ctrl-C to stop.",
        "",
    ]
    return "\n".join(lines)


def _provision(
    *, manager_url: str, tenant: str, target, pool: str, size: int, password: str,
    profile: str, generated: bool, ready_timeout: float, pg_port: int, pg_data_dir: str,
    echo, attached: bool = False, node_timeout: float = 60.0,
    node_sleep=time.sleep, node_now=time.monotonic,
) -> bool:
    """Wait for the manager, log in, ensure tenant/database/pool, persist the
    session, print the banner. Returns True once the banner has printed, False
    on any failure arm.

    Never raises: this runs on a background thread whose exception would be
    invisible, and the manager must stay up either way so the user can read the
    relayed log and re-run. Failures are reported with the equivalent manual
    command. The background-thread caller (_spawn_provisioning) ignores the
    return value; attach mode (B1, #100) runs this inline in the foreground and
    needs it to decide its own exit code.

    `node_timeout`/`node_sleep`/`node_now` thread through to
    `wait_node_routable` (real `time.sleep`/`time.monotonic` by default, same
    as every call site today); tests inject a no-op sleep and a finite tick
    clock so an unexpected extra `/api/pool/list` call raises `StopIteration`
    immediately instead of really sleeping out a 60s timeout.
    """
    settings = Settings(manager_url=manager_url)
    client = RestClient(settings)
    try:
        wait_ready(client, timeout_s=ready_timeout)
        login = client.request(
            "POST", "/api/auth/login", body={"username": _ADMIN_USER, "password": password}
        )
        token = login["token"]
        settings.token = token
        client = RestClient(settings)
        ensure_tenant(client, tenant)
        ensure_database(client, tenant, target)
        db_full = composed_db_name(tenant, target.name)
        ensure_pool(client, tenant, db_full, pool, size)
        # On a RESTART the pool row exists but a respawned node may still be
        # seconds from healthy; wait for one before printing connect strings
        # that would otherwise briefly fail. Never fails provisioning over it.
        if not wait_node_routable(
            client, tenant, db_full, pool,
            timeout_s=node_timeout, sleep=node_sleep, now=node_now,
        ):
            echo("note: node still warming; the first query may briefly fail")
        edge = client.request("GET", "/api/config/client") or {}
        edge_host = edge.get("flightSqlHost") or ""
        if edge_host in ("", "0.0.0.0"):
            edge_host = urlparse(manager_url).hostname or "localhost"
        edge_port = int(edge.get("flightSqlPort", 31338))
        save_profile(
            profile,
            {
                "manager_url": manager_url,
                "token": token,
                "sql_user": _ADMIN_USER,
                # The login just above authenticated in the SYSTEM realm (the seeded
                # admin is a superuser row, tenant IS NULL): the FlightSQL handshake
                # has no fallback between realms, so without this a `qod sql` reusing
                # this profile would authenticate in the tenant realm instead - where
                # no admin row exists - and fail with "Invalid password". tenant/pool
                # stay as-is: they are routing headers, not the auth realm.
                "superuser": True,
                "tenant": tenant,
                "pool": pool,
                "edge_host": edge_host,
                "edge_port": edge_port,
                "edge_tls": edge.get("flightSqlTls", True),
            },
        )
        echo(
            _banner(
                tenant=tenant, db=db_full, pool=pool, size=size, password=password,
                generated=generated, edge_host=edge_host, edge_port=edge_port,
                manager_url=manager_url, pg_port=pg_port, pg_data_dir=pg_data_dir,
                description=target.description, attached=attached,
            )
        )
        return True
    except ProvisionError as exc:
        echo(f"\nqod serve: {exc.step} failed: {exc.detail}")
        if exc.manual:
            echo(f"  finish by hand: {exc.manual}")
        echo("  the manager is still running; re-run qod serve to resume, or Ctrl-C to stop.")
        return False
    except ApiError as exc:
        echo(f"\nqod serve: provisioning failed: {exc}")
        echo("  the manager is still running; re-run qod serve to resume, or Ctrl-C to stop.")
        return False
    except Exception as exc:  # noqa: BLE001 - never raises, see the docstring above.
        echo(f"\nqod serve: provisioning failed unexpectedly: {exc!r}")
        echo("  the manager is still running; re-run qod serve to resume, or Ctrl-C to stop.")
        return False


def _spawn_provisioning(**kwargs) -> None:
    """Seam: tests replace this so no thread polls a manager that never boots."""
    thread = threading.Thread(target=_provision, kwargs=kwargs, daemon=True)
    thread.start()


def serve(
    ctx: typer.Context,
    target: str = typer.Argument(
        None,
        help="What to serve: a .duckdb file, a parquet/csv file, a directory or glob of them, "
        "an s3://, gs:// or az:// prefix, or nothing for a fresh empty DuckLake.",
    ),
    tenant: str = typer.Option("default", "--tenant", help="Tenant to provision under."),
    name: str = typer.Option(None, "--name", help="Database name; default is derived from TARGET."),
    pool: str = typer.Option("bi", "--pool", help="Pool name."),
    schema: str = typer.Option("main", "--schema", help="Schema inside a .duckdb file."),
    kind: str = typer.Option(
        None, "--kind",
        help="Override the inferred shape for a directory or a remote prefix: pass 'ducklake' "
        "when it holds a DuckLake's data files rather than loose parquet.",
    ),
    size: int = typer.Option(1, "--size", help="Nodes in the pool. A duckdb-file must stay at 1."),
    table: list[str] = typer.Option(
        [], "--table", metavar="NAME=GLOB",
        help="Explicit view for a multi-table remote layout. Repeatable.",
    ),
    access_key_id: str = typer.Option(
        None, "--access-key-id",
        help="Object-store credential: s3 access key id, gs HMAC key id, or az storage "
        "account name.",
    ),
    secret_access_key: str = typer.Option(
        None, "--secret-access-key",
        help="Object-store credential: s3 secret access key, gs HMAC secret, or az "
        "storage account key.",
    ),
    region: str = typer.Option(None, "--region", help="Object-store region (s3 only)."),
    endpoint: str = typer.Option(
        None, "--endpoint", help="S3-compatible endpoint, e.g. MinIO (s3 only)."
    ),
    pg_port: int = typer.Option(
        None, "--pg-port", help="Embedded Postgres port. Default 25432, or QOD_PG_EMBEDDED_PORT."
    ),
    pg_data_dir: str = typer.Option(
        None, "--pg-data-dir", help="Embedded Postgres data dir; default <data-dir>/pg, "
        "or QOD_PG_EMBEDDED_DATA_DIR.",
    ),
    ready_timeout: float = typer.Option(
        180.0, "--ready-timeout", help="Seconds to wait for the manager before giving up."
    ),
    version: str = typer.Option(
        None, "--version", envvar="QOD_VERSION", help="Manager release to run."
    ),
    jar: Path = typer.Option(None, "--jar", help="Run this local jar instead of downloading."),
    demo: bool = typer.Option(
        False,
        "--demo",
        help="Run the self-contained demo instead: embedded ephemeral Postgres, seeded "
        "TPC-H, RLS/CLS showcase. Takes no TARGET; all state is deleted on exit. Other "
        "serve flags (--tenant, --size, object-store credentials, ...) are ignored in "
        "demo mode.",
    ),
):
    """Serve your own data through a fresh, persistent gateway in one command.

    Provisions tenant/database/pool around TARGET on an embedded Postgres, so
    nothing external is required. Re-running is safe: every step creates only what
    is missing, so `qod serve ./other.duckdb` adds a second database beside the
    first. Ctrl-C tears the manager and its nodes down gracefully. With --demo,
    runs the self-contained throwaway demo (sample data, insecure by design)
    instead. If a manager is already running at the configured URL (loopback
    only), serve provisions straight into it instead of booting a second JVM;
    `qod stop` still stops it.

    Running against your own Postgres instead? Use qod start.
    """
    try:
        from .. import __version__
        from ..launcher import newer_release_hint

        hint = newer_release_hint(__version__)
        if hint:
            typer.echo(hint, err=True)
    except Exception:
        pass  # purely decorative; must never block a serve

    manager_url = ctx.obj.settings.manager_url

    if demo:
        if target is not None:
            typer.echo(
                "error: qod serve --demo takes no TARGET (the demo seeds its own sample "
                "data); drop --demo to serve your own data.",
                err=True,
            )
            raise typer.Exit(1)
        if _manager_running(manager_url):
            # The demo is ephemeral and deliberately insecure; attaching it to
            # someone else's already-running (possibly persistent) manager would
            # mix that posture into a gateway that was never meant to have it.
            typer.echo(
                f"error: a manager is already running at {manager_url}; stop it first "
                "(qod stop) before running the demo",
                err=True,
            )
            raise typer.Exit(1)
        run_demo(ctx, version, jar)
        return

    # F4: the server stores tenants lowercase (HandlerResolvers.resolveTenantId), so
    # canonicalizing here once keeps ensure_pool's client-side match, the banner, the
    # JDBC string, and the saved profile all agreeing with what the server returns -
    # a mixed-case --tenant would otherwise miss the existing pool on a re-run and
    # hit a server "already exists" error instead of the intended noop.
    tenant = tenant.lower()

    try:
        resolved = resolve_target(
            target,
            kind=kind,
            name=name,
            schema=schema,
            tables=list(table),
            object_store=_object_store(
                _scheme_family(target), access_key_id, secret_access_key, region, endpoint
            ),
            data_root=launcher.default_data_dir(),
        )
    except TargetError as exc:
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)

    tail_note = _credential_tail_note(target, list(table))
    if tail_note:
        typer.echo(tail_note, err=True)

    # Mirrors Names.normalizeTenantDbName's refusal: DuckDB cannot attach a catalog
    # under the name of an existing one. Caught here so the failure costs no JVM boot.
    if resolved.name.lower() == tenant.lower():
        typer.echo(
            f"error: the database name {resolved.name!r} would equal the tenant name, which "
            "DuckDB refuses (a catalog cannot shadow an existing one). Pass --name.",
            err=True,
        )
        raise typer.Exit(1)

    # DuckDB's file lock is single-writer: a second node attaching the same
    # .duckdb file read-write would fail to attach at all. Caught here so the
    # failure costs no JVM boot, same as the tenant-name check above.
    if resolved.kind == "duckdb-file" and size > 1:
        typer.echo(
            f"error: --size {size} is not possible for a .duckdb file target: DuckDB's "
            "single-writer file lock means only one node can attach it read-write. Use "
            "--size 1, or serve the data as a DuckLake to scale out.",
            err=True,
        )
        raise typer.Exit(1)

    if _manager_running(manager_url):
        if not _is_loopback(manager_url):
            # M7: serve only ever provisions into a LOCAL gateway - a manager
            # running at a remote (non-loopback) URL is almost certainly a
            # `qod login` profile against someone else's deployment, and
            # attaching would quietly create tenant/db/pool rows there with a
            # local filesystem dataPath its nodes cannot read, using whatever
            # QOD_ADMIN_PASSWORD happens to be set locally. Refuse outright;
            # point at the admin flows that are meant for a remote manager.
            typer.echo(
                f"error: a manager is already running at {manager_url}, but qod serve only "
                "provisions into a local (loopback) gateway; for a remote manager, use "
                "the admin flows instead: qod login, then qod tenant/database/pool create",
                err=True,
            )
            raise typer.Exit(1)
        # Attach mode (B1, #100): a manager is already up, so provision into it
        # in the foreground instead of booting a second JVM. A fresh password
        # cannot possibly match an already-running manager, so this path never
        # generates one - only a real env var or an earlier `qod serve`'s stored
        # value will do. Reuses _resolve_admin_password so the env-then-stored
        # precedence has exactly one implementation.
        password, generated = _resolve_admin_password()
        if generated:
            typer.echo(
                f"error: a manager is already running at {manager_url}, but no admin "
                "password is available to attach with; export QOD_ADMIN_PASSWORD or run "
                "qod setup / qod login",
                err=True,
            )
            raise typer.Exit(1)
        ok = _provision(
            manager_url=manager_url,
            tenant=tenant,
            target=resolved,
            pool=pool,
            size=size,
            password=password,
            profile=ctx.obj.profile,
            generated=False,
            ready_timeout=ready_timeout,
            pg_port=0,
            pg_data_dir="",
            echo=lambda line: typer.echo(line, err=True),
            attached=True,
        )
        if not ok:
            raise typer.Exit(1)
        return

    java = resolve_java()
    jar_path = jar.resolve() if jar is not None else resolve_jar(version)

    app_home = launcher.default_cache_dir()
    try:
        duckdb_bin = launcher.ensure_duckdb_cli(app_home)
        libduckdb = launcher.ensure_libduckdb(app_home)
    except Exception as exc:
        typer.echo(f"could not provision duckdb: {exc}", err=True)
        raise typer.Exit(1)
    spawn_sh, spawn_ps1 = launcher.materialize_spawn_scripts(app_home / "scripts")

    state_dir = launcher.default_data_dir()
    state_dir.mkdir(parents=True, exist_ok=True)

    password, generated = _resolve_admin_password()
    if generated:
        # Persisted BEFORE the manager boots: the seeded password must survive a
        # restart, or the banner's credentials stop working on the second run.
        save_start_env({"QOD_ADMIN_PASSWORD": password})

    # Precedence for --pg-port/--pg-data-dir mirrors the rest of the CLI: explicit
    # flag > real env var / a value persisted by `qod setup` > built-in default.
    # The flags default to None so a value already present in base_env is not
    # silently clobbered by an indistinguishable flag default.
    base_env = {**load_start_env(), **os.environ}
    if pg_port is not None:
        effective_pg_port = pg_port
    else:
        raw_pg_port = base_env.get("QOD_PG_EMBEDDED_PORT", "25432")
        try:
            effective_pg_port = int(raw_pg_port)
        except ValueError:
            # A pre-flight refusal, not a raw traceback: QOD_PG_EMBEDDED_PORT can come
            # from a real env var or a `qod setup --set` typo, neither of which the
            # user necessarily typed on this command line.
            typer.echo(
                f"error: QOD_PG_EMBEDDED_PORT is not a number: {raw_pg_port}", err=True
            )
            raise typer.Exit(1)
    # `or` (not a dict default) so an empty-string env/persisted value - a plausible
    # `export QOD_PG_EMBEDDED_DATA_DIR=` typo - falls to the built-in default instead
    # of landing the pgdata in cwd-relative nonsense.
    effective_pg_dir = (
        pg_data_dir
        if pg_data_dir is not None
        else (base_env.get("QOD_PG_EMBEDDED_DATA_DIR") or str(state_dir / "pg"))
    )

    env = launcher.runtime_env(
        base_env, app_home, duckdb_bin, spawn_sh, spawn_ps1, libduckdb_lib=libduckdb
    )
    env.setdefault("QOD_DUCKLAKE_DATA_PATH", str(state_dir / "ducklake" / "data"))
    env["QOD_PG_EMBEDDED"] = "true"
    env["QOD_PG_EMBEDDED_PORT"] = str(effective_pg_port)
    env["QOD_PG_EMBEDDED_DATA_DIR"] = effective_pg_dir
    env["QOD_ADMIN_PASSWORD"] = password
    # quack-on-demand.acl.enabled defaults to FALSE, so a persistent install has to
    # ask for it. TLS and DB auth are already on by default. A real env var or a
    # persisted `qod setup` value wins over this default.
    env.setdefault("QOD_ACL_ENABLED", "true")
    if env.get("QOD_ACL_ENABLED", "true").lower() != "true":
        typer.echo(
            f"WARN: ACL is disabled by QOD_ACL_ENABLED={env['QOD_ACL_ENABLED']} (from the "
            "environment or qod setup); this gateway will not enforce table permissions.",
            err=True,
        )

    # chdir is process-global; do it before spawning the provisioning thread so
    # that thread never observes a cwd flip mid-run.
    os.chdir(state_dir)

    _spawn_provisioning(
        manager_url=manager_url,
        tenant=tenant,
        target=resolved,
        pool=pool,
        size=size,
        password=password,
        profile=ctx.obj.profile,
        generated=generated,
        ready_timeout=ready_timeout,
        pg_port=effective_pg_port,
        pg_data_dir=effective_pg_dir,
        echo=lambda line: typer.echo(line, err=True),
    )

    _exec(
        launcher.build_jar_command(
            java, str(jar_path), list(ctx.args), java_opts=env.get("JAVA_OPTS")
        ),
        env,
    )
