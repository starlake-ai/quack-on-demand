"""`qod serve <target>`: one command from your own data to a queryable gateway.

Boots a manager on a PERSISTENT embedded Postgres (QOD_PG_EMBEDDED, see
ai.starlake.quack.boot.EmbeddedControlPlane) so there is no external prerequisite,
then provisions a tenant, database, and pool around whatever the target points at
and prints the client connection strings.

Without `--demo` this is not the demo: `qod serve --demo` (and its deprecated alias
`qod start --demo`) is ephemeral, seeded with TPC-H, and deliberately insecure (its
posture comes from DemoConfig.overlay and lives only on that code path); the default
`qod serve` path is persistent and keeps the normal secure posture: TLS on, DB auth
on, ACL on, an admin password chosen at first boot instead of 'admin'.

The booting manager provisions itself: this command writes the three create
bodies to a spec file named by QOD_SERVE_PROVISION, and the manager's add-only
ServeProvisionHook creates what is missing at boot and writes an outcome file
beside it. A thread beside the output relay waits for that outcome and prints
the banner, because `_run_supervised` owns the foreground for the JVM's
lifetime. Attach mode (a manager already running) provisions through REST
instead, as the caller. Every step creates only what is missing, so a failed or
interrupted run is resumed by simply re-running the command.
"""

from __future__ import annotations

import os
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import httpx
import typer

from .. import admin_password, launcher, starflow
from ..config import Settings, load_settings, load_start_env, save_profile
from ..rest import ApiError, RestClient
from ..serve_provision import (
    ANOTHER_NAME,
    SPEC_FILE,
    ProvisionError,
    ensure_database,
    ensure_pool,
    ensure_tenant,
    finish_by_hand,
    outcome_path,
    provision_by_hand,
    provision_spec,
    wait_node_routable,
    wait_outcome,
    wait_ready,
    write_spec,
)
from ..serve_target import TargetError, composed_db_name
from ..serve_target import resolve as resolve_target
from ._launch import _exec, refuse_a_static_api_key, resolve_jar, resolve_java
from . import _starflow
from .demo import run_demo

# The seeded superuser. QOD_ADMIN_USERNAME defaults to "admin@localhost.local,admin",
# so both names exist; the short one is what a person types.
_ADMIN_USER = "admin"


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
    *, tenant: str, db: str, pool: str, size: int, first_boot: bool,
    edge_host: str, edge_port: int, manager_url: str, pg_port: int, pg_data_dir: str,
    description: str, attached: bool = False, quack_port: int = 0,
    admin_names: list[str] | None = None,
) -> str:
    """The connect snippet. The admin password never appears: it is chosen at
    the first boot (FIRST_BOOT), passed to the manager once and stored nowhere,
    so the banner only says where it came from and, on later boots, how to
    recover it (qod admin reset-password).

    The connect lines (JDBC/ADBC/ODBC/UI) are styled (bold, colored) so they
    stand out for copy-pasting; click auto-strips the ANSI codes when the
    destination isn't a terminal, so piped output and CI logs stay plain text.

    ATTACHED (B1, #100): this run provisioned into an already-running manager
    rather than booting its own. The embedded-postgres lines make no sense there
    - that manager's control plane might be external and this process never
    touched it - so the header and tail change; everything else (credentials,
    connect strings) is identical either way.

    ADMIN_NAMES: every configured QOD_ADMIN_USERNAME (None = the built-in
    default). They were seeded as one credential, so each one is rotated
    separately: the banner names a change-password line per name."""
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
    if first_boot:
        lines.append(
            typer.style(
                "  password      : the one you just set (not stored anywhere)",
                fg=typer.colors.YELLOW,
            )
        )
    else:
        lines.append(
            typer.style(
                "  password      : set at first boot, not stored; forgot it? "
                "qod admin reset-password",
                fg=typer.colors.YELLOW,
            )
        )
        lines.append("  CLI session   : if qod commands answer 401, run qod login")
    names = admin_names if admin_names else admin_password.admin_usernames({})
    for name in names:
        lines.append(
            typer.style(
                f"  rotate password: qod auth change-password --username {name}",
                fg=typer.colors.YELLOW,
            )
        )
    # Native Quack front door: the token string is the JDBC query string. Loopback hosts
    # speak plain HTTP by default on the DuckDB side, which is what a local serve listens on.
    quack_token = f"tenant={tenant}&pool={pool}&user={_ADMIN_USER}&password=<password>&superuser=true"
    duckdb = (
        f"ATTACH 'quack:{edge_host}:{quack_port}' AS qod (TYPE quack, TOKEN '{quack_token}');"
    )
    lines += [
        "",
        typer.style(f"  JDBC {jdbc}", fg="cyan", bold=True),
        typer.style(f"  ADBC {adbc}", fg="cyan", bold=True),
        typer.style(f"  ODBC {odbc}", fg="cyan", bold=True),
    ]
    if quack_port > 0:
        lines.append(typer.style(f"  DuckDB {duckdb}", fg="cyan", bold=True))
    lines += [
        typer.style(f"  UI   {manager_url.rstrip('/')}/ui/", fg="cyan", bold=True),
        "",
        "  gateway already running; stop it with: qod stop" if attached else "  Ctrl-C to stop.",
        "",
    ]
    return "\n".join(lines)


def _failure_tail(attached: bool) -> str:
    if attached:
        return (
            "  the gateway is still running; fix the credentials (run qod login, or set "
            "QOD_TOKEN to a personal access token) and re-run qod serve"
        )
    return "  the manager is still running; re-run qod serve to resume, or Ctrl-C to stop."


# The release the launcher expects: the CLI and the manager share a version number.
_MATCHING_RELEASE = (
    "the manager release matching this CLI (`qod --version` shows it; drop an older "
    "`--jar` or `--version`)"
)


def _report_failure(exc: Exception, echo, attached: bool) -> None:
    """Both provisioning paths' failure arm: the step, its hand-run commands (one
    `finish by hand:` line each) when there are any, and its advice (where to look,
    a collision's way out) on plain lines, then where the manager stands."""
    if isinstance(exc, ProvisionError):
        echo(f"\nqod serve: {exc.step} failed: {exc.detail}")
        for command in exc.manual.splitlines():
            echo(f"  finish by hand: {command}")
        for line in exc.hint.splitlines():
            echo(f"  {line}")
    elif isinstance(exc, ApiError):
        echo(f"\nqod serve: provisioning failed: {exc}")
    else:
        echo(f"\nqod serve: provisioning failed unexpectedly: {exc!r}")
    echo(_failure_tail(attached))


def _finish(
    *, client: RestClient, manager_url: str, tenant: str, target, pool: str, size: int,
    profile: str, pg_port: int, pg_data_dir: str, echo, token: str, save_token: bool,
    first_boot: bool, attached: bool, node_timeout: float, node_sleep, node_now,
    admin_names: list[str] | None,
) -> None:
    """Both paths' tail once the tenant, database and pool exist: wait for a
    routable node when TOKEN can list pools, save the profile (the session only
    when SAVE_TOKEN), print the banner. CLIENT carries TOKEN."""
    db_full = composed_db_name(tenant, target.name)
    # On a RESTART the pool row exists but a respawned node may still be
    # seconds from healthy; wait for one before printing connect strings
    # that would otherwise briefly fail. Never fails provisioning over it, and
    # without a working credential there is no listing to wait on.
    if not token or not wait_node_routable(
        client, tenant, db_full, pool,
        timeout_s=node_timeout, sleep=node_sleep, now=node_now,
    ):
        echo("note: node still warming; the first query may briefly fail")
    edge = client.request("GET", "/api/config/client") or {}
    edge_host = edge.get("flightSqlHost") or ""
    if edge_host in ("", "0.0.0.0"):
        edge_host = urlparse(manager_url).hostname or "localhost"
    edge_port = int(edge.get("flightSqlPort", 31338))
    # 0 when the manager predates the native Quack front door or has it disabled.
    quack_port = int(edge.get("quackPort") or 0)
    save_profile(
        profile,
        {
            "manager_url": manager_url,
            # save_profile skips None: a run that did not log in keeps the
            # profile's stored session.
            "token": token if save_token else None,
            "sql_user": _ADMIN_USER,
            # The seeded admin is a SUPERUSER row (tenant IS NULL): the FlightSQL
            # handshake has no fallback between realms, so without this a `qod sql`
            # reusing this profile would authenticate in the tenant realm instead -
            # where no admin row exists - and fail with "Invalid password". tenant/pool
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
            tenant=tenant, db=db_full, pool=pool, size=size,
            first_boot=first_boot, edge_host=edge_host, edge_port=edge_port,
            quack_port=quack_port,
            manager_url=manager_url, pg_port=pg_port, pg_data_dir=pg_data_dir,
            description=target.description, attached=attached,
            admin_names=admin_names,
        )
    )


def _provision(
    *, manager_url: str, tenant: str, target, pool: str, size: int, profile: str,
    ready_timeout: float, pg_port: int, pg_data_dir: str, echo,
    token: str = "", password: str | None = None,
    first_boot: bool = False, attached: bool = False, node_timeout: float = 60.0,
    node_sleep=time.sleep, node_now=time.monotonic, admin_names: list[str] | None = None,
) -> bool:
    """Provision through REST: wait for the manager, authenticate, ensure
    tenant/database/pool, persist the profile, print the banner. Returns True
    once the banner has printed, False on any failure arm. This is attach mode's
    path (B1, #100); a booting serve's manager provisions itself
    (_provision_at_boot).

    Authentication is the caller's own: TOKEN (QOD_TOKEN, or the profile's live
    session) or PASSWORD, which logs in as the seeded admin and saves the
    resulting session to the profile. Without a password the profile's stored
    token is left as it was.

    Never raises: the manager must stay up either way so the user can read the
    relayed log and re-run. Failures are reported with the equivalent manual
    command. Attach mode runs this inline in the foreground and needs the return
    value to decide its own exit code.

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
        if password is not None:
            login = client.request(
                "POST", "/api/auth/login", body={"username": _ADMIN_USER, "password": password}
            )
            token = login["token"]
        settings.token = token
        client = RestClient(settings)
        ensure_tenant(client, tenant)
        ensure_database(client, tenant, target)
        ensure_pool(client, tenant, composed_db_name(tenant, target.name), pool, size)
        _finish(
            client=client, manager_url=manager_url, tenant=tenant, target=target, pool=pool,
            size=size, profile=profile, pg_port=pg_port, pg_data_dir=pg_data_dir, echo=echo,
            token=token, save_token=password is not None, first_boot=first_boot,
            attached=attached, node_timeout=node_timeout, node_sleep=node_sleep,
            node_now=node_now, admin_names=admin_names,
        )
        return True
    except Exception as exc:  # noqa: BLE001 - never raises, see the docstring above.
        _report_failure(exc, echo, attached)
        return False


def _provision_at_boot(
    *, manager_url: str, spec_path: Path, tenant: str, target, pool: str, size: int,
    profile: str, ready_timeout: float, pg_port: int, pg_data_dir: str, echo,
    password: str | None = None, token: str = "", first_boot: bool = False,
    admin_names: list[str] | None = None, sleep=time.sleep, now=time.monotonic,
    node_timeout: float = 60.0, node_sleep=time.sleep, node_now=time.monotonic,
) -> bool:
    """A booting serve's half of provisioning. The manager creates the tenant,
    database and pool itself (ServeProvisionHook, from the spec at SPEC_PATH), so
    this waits for /ready, then for the hook's outcome file, both within one
    READY_TIMEOUT deadline, and prints the banner, or what was refused and how to
    finish by hand. Returns True once the banner has printed.

    On a first boot (PASSWORD given) it logs in as the seeded admin, saves the
    session to the profile and uses it to wait for a routable node. A later boot
    waits with TOKEN instead (QOD_TOKEN, or the profile's unexpired session) when
    it lists pools, and saves nothing; with none that does, the banner carries the
    node-warming note.

    Never raises, like _provision: it runs on a background thread whose
    exception would be invisible, and the manager stays up either way."""
    client = RestClient(Settings(manager_url=manager_url))
    try:
        deadline = now() + ready_timeout
        wait_ready(client, timeout_s=ready_timeout, sleep=sleep, now=now)
        outcome = wait_outcome(
            outcome_path(spec_path), timeout_s=max(0.0, deadline - now()), sleep=sleep, now=now
        )
        if outcome is None:
            raise ProvisionError(
                "provisioning",
                "the manager answers /ready but wrote no provisioning outcome within "
                f"--ready-timeout ({ready_timeout:.0f}s): this manager release does not "
                f"provision at boot. Run {_MATCHING_RELEASE}, "
                "or provision by hand after qod login",
                provision_by_hand(tenant, target, pool, size),
            )
        # Consumed: the database body can carry object-store credentials, which
        # need not stay on disk past the boot that read them.
        spec_path.unlink(missing_ok=True)
        if outcome["status"] != "ok":
            step = str(outcome.get("step") or "")
            reason = str(outcome.get("reason") or "the manager gave no reason")
            if step == "spec":
                raise ProvisionError(
                    "provisioning",
                    "the manager could not read the provisioning file the launcher wrote: "
                    f"{reason} (a launcher/manager version mismatch: run {_MATCHING_RELEASE})",
                )
            # A collision's reason already names its fix (another --name or --pool), and a
            # create-by-hand command would collide again.
            manual = (
                "" if ANOTHER_NAME in reason
                else finish_by_hand(step, tenant, target, pool, size)
            )
            raise ProvisionError(f"create {step}" if step else "provisioning", reason, manual)
        if password is not None:
            login = client.request(
                "POST", "/api/auth/login", body={"username": _ADMIN_USER, "password": password}
            )
            token = login["token"]
        elif not _lists_pools(manager_url, token):
            token = ""
        _finish(
            client=RestClient(Settings(manager_url=manager_url, token=token)),
            manager_url=manager_url, tenant=tenant, target=target, pool=pool, size=size,
            profile=profile, pg_port=pg_port, pg_data_dir=pg_data_dir, echo=echo,
            token=token, save_token=password is not None, first_boot=first_boot,
            attached=False, node_timeout=node_timeout, node_sleep=node_sleep,
            node_now=node_now, admin_names=admin_names,
        )
        return True
    except Exception as exc:  # noqa: BLE001 - never raises, see the docstring above.
        _report_failure(exc, echo, attached=False)
        return False


def _lists_pools(manager_url: str, token: str) -> bool:
    """Whether TOKEN can list pools, the listing the node wait polls: one look,
    so a refused or under-privileged credential costs no wait."""
    if not token:
        return False
    try:
        RestClient(Settings(manager_url=manager_url, token=token)).request("GET", "/api/pool/list")
    except ApiError:
        return False
    return True


def _spawn_provisioning(**kwargs) -> None:
    """Seam: tests replace this so no thread polls a manager that never boots."""
    thread = threading.Thread(target=_provision_at_boot, kwargs=kwargs, daemon=True)
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
    with_starflow: bool = _starflow.WITH_STARFLOW,
    starflow_home: str = _starflow.STARFLOW_HOME,
    starflow_version: str = _starflow.STARFLOW_VERSION,
    starflow_port: int = _starflow.STARFLOW_PORT,
    starflow_url: str = _starflow.STARFLOW_URL,
    starflow_env_file: Path = _starflow.STARFLOW_ENV_FILE,
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

    With --with-starflow, Starflow (the Starlake API + UI) runs next to the
    manager in a `starlake` database on the embedded Postgres, paired for SSO,
    REST and FlightSQL; it is installed on first use and stopped by Ctrl-C and
    qod stop. Refused with --demo and when a manager is already running.

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

    # Before anything launches: the boot path, attach mode and --demo alike.
    refuse_a_static_api_key()

    manager_url = ctx.obj.settings.manager_url

    sf_request = _starflow.request(
        with_starflow, starflow_home, starflow_version, starflow_port, starflow_url,
        starflow_env_file,
    )

    if demo:
        if sf_request is not None:
            typer.echo(
                "error: --with-starflow cannot run with the demo (ephemeral, insecure by "
                "design); drop --demo",
                err=True,
            )
            raise typer.Exit(1)
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

    admin_password.migrate_stored_password(lambda line: typer.echo(line, err=True))

    if _manager_running(manager_url):
        if sf_request is not None:
            # qod cannot know, or inject, the running manager's session secret,
            # so the pair could never authenticate.
            typer.echo(
                f"error: a manager is already running at {manager_url}; --with-starflow "
                "needs to launch it itself: stop it first (qod stop)",
                err=True,
            )
            raise typer.Exit(1)
        if not _is_loopback(manager_url):
            # M7: serve only ever provisions into a LOCAL gateway - a manager
            # running at a remote (non-loopback) URL is almost certainly a
            # `qod login` profile against someone else's deployment, and
            # attaching would quietly create tenant/db/pool rows there with a
            # local filesystem dataPath its nodes cannot read, using whatever
            # admin credential happens to be set locally. Refuse outright;
            # point at the admin flows that are meant for a remote manager.
            typer.echo(
                f"error: a manager is already running at {manager_url}, but qod serve only "
                "provisions into a local (loopback) gateway; for a remote manager, use "
                "the admin flows instead: qod login, then qod tenant/database/pool create",
                err=True,
            )
            raise typer.Exit(1)
        # Attach mode (B1, #100): a manager is already up, so provision into it
        # in the foreground instead of booting a second JVM, through REST and as
        # the caller: the settings token (QOD_TOKEN, else the profile's session),
        # else the admin password prompted on a terminal.
        start_env = {**load_start_env(), **os.environ}
        # An expired session JWT counts as absent, so a terminal still reaches
        # the prompt instead of provisioning with a token the manager will 401.
        token = admin_password.usable_session_token(load_settings(ctx.obj.profile).token)
        password = None
        if not token:
            if admin_password.is_interactive():
                password = typer.prompt("Admin password", hide_input=True)
            else:
                typer.echo(
                    f"error: a manager is already running at {manager_url}, but no admin "
                    "credential is available to attach with; run qod login, set QOD_TOKEN, "
                    "or run qod serve in a terminal",
                    err=True,
                )
                raise typer.Exit(1)
        ok = _provision(
            manager_url=manager_url,
            tenant=tenant,
            target=resolved,
            pool=pool,
            size=size,
            profile=ctx.obj.profile,
            ready_timeout=ready_timeout,
            pg_port=0,
            pg_data_dir="",
            echo=lambda line: typer.echo(line, err=True),
            attached=True,
            token=token,
            password=password,
            admin_names=admin_password.admin_usernames(start_env),
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
        node_bin = launcher.node_binary(jar_path, app_home)
    except Exception as exc:
        typer.echo(f"could not provision the node runtime: {exc}", err=True)
        raise typer.Exit(1)
    spawn_sh, spawn_ps1 = launcher.materialize_spawn_scripts(app_home / "scripts")

    state_dir = launcher.default_data_dir()
    state_dir.mkdir(parents=True, exist_ok=True)

    # Precedence for --pg-port/--pg-data-dir mirrors the rest of the CLI: explicit
    # flag > real env var / a value persisted by `qod setup` > built-in default.
    # The flags default to None so a value already present in base_env is not
    # silently clobbered by an indistinguishable flag default.
    base_env = {**load_start_env(), **os.environ}
    sf_plan = _starflow.prepare(sf_request, base_env, java) if sf_request else None
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
    # First boot = the embedded control plane has no data dir yet: the manager
    # will seed the admin row from this password, once. It is never stored.
    first_boot = not (Path(effective_pg_dir) / "pgdata").is_dir()
    password = None
    # A later boot waits for a routable node with the caller's own credential
    # (QOD_TOKEN, else the profile's session), as attach mode provisions with
    # it; an expired session counts as none.
    restart_token = ""
    if first_boot:
        password = admin_password.first_boot_password(
            os.environ.get(admin_password.KEY), admin_password.is_interactive()
        )
        if password is None:
            typer.echo(admin_password.refusal("qod serve"), err=True)
            raise typer.Exit(1)
    else:
        restart_token = admin_password.usable_session_token(load_settings(ctx.obj.profile).token)

    env = launcher.runtime_env(
        base_env, duckdb_bin, spawn_sh, spawn_ps1, libduckdb_lib=libduckdb, qod_node_bin=node_bin
    )
    env.setdefault("QOD_DUCKLAKE_DATA_PATH", str(state_dir / "ducklake" / "data"))
    env["QOD_PG_EMBEDDED"] = "true"
    env["QOD_PG_EMBEDDED_PORT"] = str(effective_pg_port)
    env["QOD_PG_EMBEDDED_DATA_DIR"] = effective_pg_dir
    if password is not None:
        env[admin_password.KEY] = password
    if sf_plan is not None:
        env.update(starflow.manager_env(sf_plan.url, sf_plan.secret))
    # The manager provisions the tenant, database and pool itself, at boot
    # (ServeProvisionHook), from this spec: no credential is needed for it. An
    # outcome left by an earlier boot is deleted first, or the provisioning thread
    # could read it as this boot's.
    spec_path = state_dir / SPEC_FILE
    write_spec(spec_path, provision_spec(tenant, resolved, pool, size))
    outcome_path(spec_path).unlink(missing_ok=True)
    env["QOD_SERVE_PROVISION"] = str(spec_path.absolute())
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
        spec_path=spec_path,
        tenant=tenant,
        target=resolved,
        pool=pool,
        size=size,
        profile=ctx.obj.profile,
        ready_timeout=ready_timeout,
        pg_port=effective_pg_port,
        pg_data_dir=effective_pg_dir,
        echo=lambda line: typer.echo(line, err=True),
        password=password,
        token=restart_token,
        first_boot=first_boot,
        admin_names=admin_password.admin_usernames(env),
    )

    if sf_plan is not None:
        _starflow.start_after_ready(
            sf_plan,
            mgr_env=env,
            # zonky trust auth: the password is a placeholder (EmbeddedControlPlane).
            pg=starflow.PgCoords(
                host="localhost", port=effective_pg_port, user="postgres", password="postgres"
            ),
            ready_timeout=ready_timeout,
        )

    try:
        _exec(
            launcher.build_jar_command(
                java, str(jar_path), list(ctx.args), java_opts=env.get("JAVA_OPTS")
            ),
            env,
        )
    finally:
        # The spec can carry object-store credentials. The provisioning thread
        # deletes it once the manager reported; whatever else ended the run
        # (Ctrl-C, a refused boot, a release that never reads it, a ready
        # timeout), it does not outlive the manager.
        spec_path.unlink(missing_ok=True)
