"""Resolve a `qod serve` target string into the tenant-db shape that serves it.

Pure by design: the only I/O is `pathlib` stat plus a directory listing for LOCAL
targets, so every rule here is table-testable without a manager.

Remote prefixes are deliberately never listed. Enumerating an s3:// prefix needs
credentials and a cloud SDK in the CLI, and `pyproject.toml` already carries a
platform-conditional exclusion to keep the dependency set light enough for
`uvx qod` to work everywhere. So a remote prefix resolves to one glob view and
`--table NAME=GLOB` covers multi-table layouts explicitly.
"""

from __future__ import annotations

import glob as _glob
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

# Mirrors ai.starlake.quack.model.Names: 1..63 chars, leading letter or
# underscore, then letters, digits, underscore. Kept in sync by
# test_composed_db_name_mirrors_the_server and require_valid_name's tests.
_NAME_RE = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")
_NAME_MAX = 63

_REMOTE_SCHEMES = (
    "s3://", "s3a://", "r2://", "gs://", "gcs://", "az://", "azure://", "abfss://",
)
_DUCKDB_SUFFIXES = (".duckdb", ".ddb", ".db")
_PARQUET_SUFFIXES = (".parquet", ".pq")
_CSV_SUFFIXES = (".csv", ".tsv", ".csv.gz", ".tsv.gz")
# Only .gz is reachable through _CSV_SUFFIXES above; extend both together.
_COMPRESSION_SUFFIXES = (".gz",)
_GLOB_CHARS = "*?["
_KINDS = ("ducklake", "duckdb-file", "memory")


class TargetError(ValueError):
    """A target the user must fix. Raised before anything is launched."""


@dataclass(frozen=True)
class ServeTarget:
    kind: str  # ducklake | duckdb-file | memory
    name: str  # tenant-db SUFFIX; the server composes <tenant>_<name>
    data_path: str
    metastore: dict = field(default_factory=dict)
    object_store: dict = field(default_factory=dict)
    init_sql: str = ""
    description: str = ""


def sanitize_name(raw: str) -> str:
    """Best-effort slug of a path stem into a Postgres identifier."""
    slug = re.sub(r"[^a-zA-Z0-9_]", "_", (raw or "").strip()).strip("_").lower()
    slug = re.sub(r"_+", "_", slug)
    if slug and slug[0].isdigit():
        slug = "db_" + slug
    return slug[:_NAME_MAX]


def require_valid_name(name: str, origin: str) -> str:
    if not name or len(name) > _NAME_MAX or not _NAME_RE.match(name):
        raise TargetError(
            f"cannot derive a database name from {origin!r} (got {name!r}). Names follow "
            "Postgres identifier rules: 1-63 characters, starting with a letter or "
            "underscore, then letters, digits, or underscores. Pass --name explicitly."
        )
    return name


def composed_db_name(tenant: str, suffix: str) -> str:
    """Mirror of Names.normalizeTenantDbName: the server stores <tenant>_<suffix>
    unless the suffix already carries the prefix. Needed so `qod serve` can match
    an existing row from `database/list` (which returns the COMPOSED name) against
    the suffix `database/create` takes."""
    t, s = tenant.lower(), suffix.lower()
    return s if s.startswith(t + "_") else f"{t}_{s}"


# Mirror of ai.starlake.quack.model.TenantDb.DataPathForbiddenChars. After
# .as_posix() a backslash can no longer occur from a path separator, so a
# backslash reaching here means the target's own name is genuinely hostile.
_DATA_PATH_FORBIDDEN_CHARS = "'\";\\\n\r"


def _check_data_path(value: str) -> str:
    for ch in _DATA_PATH_FORBIDDEN_CHARS:
        if ch in value:
            raise TargetError(
                f"the server rejects dataPath containing {ch!r}; rename the target or move it"
            )
    return value


def _sql_str(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _sql_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _stem(filename: str) -> str:
    name = filename
    for suffix in _COMPRESSION_SUFFIXES:
        if name.lower().endswith(suffix):
            name = name[: -len(suffix)]
    return Path(name).stem


def _reader_for(filename: str) -> str | None:
    low = filename.lower()
    if low.endswith(_PARQUET_SUFFIXES):
        return "read_parquet"
    if low.endswith(_CSV_SUFFIXES):
        return "read_csv"
    return None


def _view(name: str, reader: str, target: str, hive: bool) -> str:
    # A secure view: tenants read the files through a read grant on the view, the reader running
    # with the view owner's rights (the node's init), not with theirs on a path they never
    # authorized. The reader is qualified: unqualified, a macro of that name a user creates in the
    # view's schema would be what the view binds, and the node refuses a secure view naming one (so
    # no served file could be read any more).
    args = _sql_str(target) + (", hive_partitioning = true" if hive else "")
    body = f"SELECT * FROM system.main.{reader}({args})"
    return f"CREATE OR REPLACE SECURE VIEW {_sql_ident(name)} AS {body};"


def _parse_tables(tables: list[str]) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for item in tables:
        table_name, sep, target = item.partition("=")
        if not sep or not table_name.strip() or not target.strip():
            raise TargetError(f"--table expects NAME=GLOB, got {item!r}")
        out.append((table_name.strip(), target.strip()))
    return out


def _table_views(tables: list[str]) -> list[str]:
    return [
        _view(require_valid_name(sanitize_name(n), n), "read_parquet", g, True)
        for n, g in _parse_tables(tables)
    ]


def _views_for_directory(root: Path) -> list[str]:
    """One view per immediate child: a readable FILE becomes a view over that file,
    a SUBDIRECTORY holding parquet at any depth becomes a hive-partitioned view.
    Entries starting with '.' or '_' are skipped, which covers _delta_log,
    _SUCCESS, .DS_Store and friends. An entry whose stem sanitizes to an empty
    name (all-punctuation) is skipped too, rather than emitting `VIEW ""`."""
    stmts: list[str] = []
    for child in sorted(root.iterdir()):
        if child.name.startswith((".", "_")):
            continue
        if child.is_file():
            reader = _reader_for(child.name)
            if reader is None:
                continue
            view_name = sanitize_name(_stem(child.name))
            if not view_name:
                continue
            # Local paths are always emitted POSIX-style: the server rejects
            # backslashes in dataPath (TenantDb.DataPathForbiddenChars), and both
            # DuckDB and the JVM accept forward slashes on Windows, so `.as_posix()`
            # (not `str(...)`) is what turns a resolved Path into wire form
            # everywhere in this module.
            stmts.append(_view(view_name, reader, child.resolve().as_posix(), False))
        elif child.is_dir() and next(child.rglob("*.parquet"), None) is not None:
            view_name = sanitize_name(child.name)
            if not view_name:
                continue
            stmts.append(
                _view(
                    view_name,
                    "read_parquet",
                    f"{child.resolve().as_posix()}/**/*.parquet",
                    True,
                )
            )
    if not stmts:
        raise TargetError(
            f"no parquet or csv files found under {root}. Point qod serve at a directory "
            "holding .parquet/.csv files, at a single such file, or at a .duckdb file."
        )
    return stmts


def _as_ducklake(display: str, data_path: str, db: str, object_store: dict) -> ServeTarget:
    """`--kind ducklake`: the target is a DuckLake DATA PATH (a local directory or a
    remote prefix) whose catalog tables live in Postgres, not loose parquet to view
    over. The metastore is left empty on purpose: PoolSupervisor fills it from
    quack-on-demand.defaultMetastore (the embedded Postgres, under `qod serve`) and
    force-sets dbName to the composed name."""
    return ServeTarget(
        kind="ducklake",
        name=db,
        data_path=_check_data_path(data_path),
        object_store=dict(object_store),
        description=f"DuckLake warehouse at {display}",
    )


def _resolve_remote(
    target: str, name: str | None, tables: list[str], object_store: dict, kind: str | None
) -> ServeTarget:
    # DuckDB and ObjectStoreSecret both know "gs"; "gcs" is accepted as a
    # user-friendly alias and normalized before it becomes the dataPath.
    if target.lower().startswith("gcs://"):
        target = "gs://" + target[len("gcs://") :]
    prefix = target.rstrip("/")
    last = prefix.rsplit("/", 1)[-1] or "remote"
    db = require_valid_name(name or sanitize_name(last), target)
    if kind == "ducklake":
        return _as_ducklake(prefix + "/", prefix + "/", db, object_store)
    if kind is not None and kind != "memory":
        raise TargetError(
            f"--kind {kind} only applies to a local directory holding a DuckLake's data "
            "files ('ducklake'); a remote prefix is either that or loose parquet/csv views "
            "('memory', the default)."
        )
    stmts = _table_views(tables) if tables else [
        _view(db, "read_parquet", f"{prefix}/**/*.parquet", True)
    ]
    return ServeTarget(
        kind="memory",
        name=db,
        # NOT a catalog: the spawn script's `memory)` arm never ATTACHes or mkdirs
        # this. It is the SCOPE for the per-database CREATE SECRET, which
        # ObjectStoreSecret.sql derives from dataPath and omits without one.
        data_path=_check_data_path(prefix + "/"),
        object_store=dict(object_store),
        init_sql="\n".join(stmts),
        description=f"{len(stmts)} view(s) over {prefix}",
    )


def _resolve_glob(target: str, name: str | None, object_store: dict) -> ServeTarget:
    # Path.resolve() only absolutizes; it does not expand glob metacharacters, so
    # it is safe to call on a pattern. A relative glob is resolved against the
    # CLI's cwd, but the emitted view SQL runs in the node process's cwd, so the
    # pattern used to match here MUST be the same absolutized (POSIX-styled)
    # expression that lands in the view, or the node would match nothing.
    pattern = Path(os.path.expanduser(target)).resolve().as_posix()
    matches = sorted(m for m in _glob.glob(pattern, recursive=True) if _reader_for(m))
    if not matches:
        raise TargetError(
            f"{target} matched no parquet or csv files. Check the pattern, and quote it so "
            "your shell does not expand it first."
        )
    reader = _reader_for(matches[0])
    db = require_valid_name(name or sanitize_name(_stem(Path(matches[0]).name)), target)
    return ServeTarget(
        kind="memory",
        name=db,
        data_path="",
        object_store=dict(object_store),
        init_sql=_view(db, reader, pattern, False),
        description=f"1 view over {target} ({len(matches)} file(s) matched)",
    )


def _resolve_local(
    target: str, name: str | None, schema: str, tables: list[str], kind: str | None,
    object_store: dict,
) -> ServeTarget:
    path = Path(os.path.expanduser(target))
    if not path.exists():
        raise TargetError(f"{path} does not exist")
    if path.is_dir():
        db = require_valid_name(name or sanitize_name(path.resolve().name), target)
        if kind == "ducklake":
            resolved = path.resolve().as_posix()
            return _as_ducklake(str(path.resolve()), resolved, db, object_store)
        if kind is not None and kind != "memory":
            raise TargetError(
                f"--kind {kind} only applies to a directory holding a DuckLake's data files "
                "('ducklake'); a directory of loose parquet/csv is 'memory', the default."
            )
        stmts = _table_views(tables) if tables else _views_for_directory(path)
        return ServeTarget(
            kind="memory",
            name=db,
            data_path="",
            object_store=dict(object_store),
            init_sql="\n".join(stmts),
            description=f"{len(stmts)} view(s) over {path.resolve()}",
        )
    if kind is not None:
        raise TargetError(
            f"--kind {kind} only applies to a directory or a remote prefix; the shape of a "
            f"single file like {path.name} is inferred from its extension."
        )
    if path.name.lower().endswith(_DUCKDB_SUFFIXES):
        db = require_valid_name(name or sanitize_name(path.stem), target)
        return ServeTarget(
            kind="duckdb-file",
            name=db,
            data_path=_check_data_path(path.resolve().as_posix()),
            metastore={"dbName": db, "schemaName": schema},
            description=f"DuckDB file {path.resolve()} (single node, read-write)",
        )
    reader = _reader_for(path.name)
    if reader is None:
        raise TargetError(
            f"cannot serve {path}: expected a .duckdb file, a .parquet/.csv file, a "
            "directory of them, or a glob."
        )
    db = require_valid_name(name or sanitize_name(_stem(path.name)), target)
    return ServeTarget(
        kind="memory",
        name=db,
        data_path="",
        object_store=dict(object_store),
        init_sql=_view(db, reader, path.resolve().as_posix(), False),
        description=f"1 view over {path.resolve()}",
    )


def resolve(
    target: str | None,
    *,
    kind: str | None = None,
    name: str | None = None,
    schema: str = "main",
    tables: list[str] | None = None,
    object_store: dict | None = None,
    data_root: Path,
) -> ServeTarget:
    """Dispatch order: URI scheme, then glob metacharacters, then a filesystem stat.

    `kind` is an override for the genuinely ambiguous targets only: a directory or
    a remote prefix can hold either loose parquet (views over it, the default) or a
    DuckLake's data files (`kind="ducklake"`). A single file's shape follows from
    its extension, so passing `kind` there is an error rather than a silent no-op.
    """
    tables = list(tables or [])
    store = dict(object_store or {})
    if kind is not None and kind not in _KINDS:
        raise TargetError(f"--kind must be one of {', '.join(_KINDS)}, got {kind!r}")
    if not target:
        db = require_valid_name(name or "main", "--name")
        root = Path(data_root) / "ducklake" / db
        return ServeTarget(
            kind="ducklake",
            name=db,
            data_path=_check_data_path(root.resolve().as_posix()),
            description=f"new DuckLake warehouse at {root}",
        )
    if target.lower().startswith(_REMOTE_SCHEMES):
        return _resolve_remote(target, name, tables, store, kind)
    if any(ch in target for ch in _GLOB_CHARS):
        if kind is not None:
            raise TargetError(
                f"--kind {kind}: only 'ducklake' or 'memory' apply to a directory or a "
                "remote prefix; the shape of a glob pattern like this one is always inferred."
            )
        return _resolve_glob(target, name, store)
    return _resolve_local(target, name, schema, tables, kind, store)
