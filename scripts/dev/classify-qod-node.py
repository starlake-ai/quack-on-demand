#!/usr/bin/env python3
"""Generates the function and setting classification of in-engine authorization (spec Section 6,
decision 11) from what qod-node resolves:

  native/qod-node/functions.json -> src/main/resources/authz/function-classes.tsv
                                    native/qod-node/file-functions.txt
                                    native/qod-node/admitted-table-functions.txt
  native/qod-node/macros.json    -> the macro and table macro rows of function-classes.tsv
  native/qod-node/settings.json  -> src/main/resources/authz/setting-classes.tsv

Run by scripts/dev/gen-qod-node-classification.sh, which writes the JSON files first; macros.json
comes from `--macros-dump <file>`, the release CLI's duckdb_functions() rows of every internal macro
and table macro with each definition's parse tree (json_serialize_sql), reduced here to the calls
and table reads of each body. The rule tables below ARE the classification: review a change to them
like code. FunctionClassesSpec and SettingClassesSpec fail until the generated tables match the
JSON.

Fail closed by construction:
  - a table function or a pragma no rule names is superuser;
  - a scalar, aggregate or window function is allowed unless a rule names it, except one whose name
    starts with a SCALAR_SUPERUSER_PREFIXES family or holds a SUSPICIOUS_SUBSTRINGS entry and is not in
    REVIEWED_ALLOWED: that is superuser (quack_ has no exception);
  - an alias is at least as strict as the function it stands for;
  - a setting is superuser unless SESSION names it; an alias takes its target's class;
  - a built-in macro or table macro (Task 19) is classified from its body: allowed only when every
    call in it, through other macros, is to a function classed allowed (no sequence function, no
    query() or query_table(), no table read), superuser when any call is to one classed superuser,
    and otherwise not classified, so the node refuses it in a bind-time slot.
Every default applied is printed under "review:": copy those lines into the commit message.

`--macro-classes <dump>` prints the class it computes for each macro of a dump, against the
function classes functions.json gives, and writes nothing (FunctionClassesSpec runs it on a fixture).
"""

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
FUNCTIONS_JSON = REPO / "native/qod-node/functions.json"
SETTINGS_JSON = REPO / "native/qod-node/settings.json"
MACROS_JSON = REPO / "native/qod-node/macros.json"
FUNCTION_TSV = REPO / "src/main/resources/authz/function-classes.tsv"
SETTING_TSV = REPO / "src/main/resources/authz/setting-classes.tsv"
FILE_FUNCTIONS = REPO / "native/qod-node/file-functions.txt"
ADMITTED_TABLE_FUNCTIONS = REPO / "native/qod-node/admitted-table-functions.txt"

# ---- table functions --------------------------------------------------------------------------------

# The catalog listings the quack client's ATTACH sync reads, which the gateway's MetadataFilterRewriter
# narrows to the principal's grants (TableExtractor.DuckDbCatalogFunctions).
LISTING_TABLE = {"duckdb_columns", "duckdb_schemas", "duckdb_tables", "duckdb_views"}

# Their arguments name the files or URLs they read: admitted on authorized locations only (decisions
# 15 and 18). FileAccessScreen.FileFunctions minus the readers that follow file names found inside
# the file (ducklake_scan, the Iceberg path readers) and sql_auto_complete (SQL text, not a location).
FILE_TABLE = {
    "avro_metadata", "glob", "parquet_bloom_probe", "parquet_file_metadata", "parquet_full_metadata",
    "parquet_kv_metadata", "parquet_metadata", "parquet_scan", "parquet_schema", "read_avro", "read_blob",
    "read_csv", "read_csv_auto", "read_duckdb", "read_json", "read_json_auto", "read_json_objects",
    "read_json_objects_auto", "read_ndjson", "read_ndjson_auto", "read_ndjson_objects", "read_parquet",
    "read_postgres_binary", "read_single_csv_file", "read_single_json_file", "read_single_parquet_file",
    "read_text", "sniff_csv",
}

# No access of their own: value generators, text parsers, engine facts, the session's own state, the
# manager's preludes (qod_session, ducklake_set_commit_message), the front door's client stream, and
# query() / query_table(), which DuckDB binds inline so the describer sees what they read.
ALLOWED_TABLE = {
    "check_peg_parser", "duckdb_available_metrics", "duckdb_coordinate_systems", "duckdb_dialects",
    "duckdb_external_resource_types", "duckdb_grammar_extensions", "duckdb_keywords",
    "duckdb_optimizers", "duckdb_prepared_statements", "duckdb_secret_type_parameters",
    "duckdb_secret_types", "duckdb_variables", "ducklake_set_commit_message", "generate_series",
    "icu_calendar_names", "json_each", "json_tree", "pg_timezone_names", "pragma_collations",
    "pragma_platform", "pragma_user_agent", "pragma_version", "qod_session", "query", "query_table",
    "range", "repeat", "repeat_row", "scan_data_from_quack_client", "sql_tokenize", "summary",
    "test_all_types", "test_vector_types", "unnest",
}

# Superusers only. Listed for review; any table function no set names is superuser anyway.
SUPERUSER_TABLE = {
    # raw memory addresses; DuckDB's internal scan
    "arrow_scan", "arrow_scan_dumb", "seq_scan",
    # node state: settings and secrets, paths, other sessions, caches, logs, extensions
    "duckdb_approx_database_count", "duckdb_connection_count", "duckdb_databases", "duckdb_eviction_queues", "duckdb_extension_repositories", "duckdb_extensions",
    "duckdb_external_file_cache", "duckdb_external_resources", "duckdb_log_contexts", "duckdb_logs",
    "duckdb_memory", "duckdb_secrets", "duckdb_settings", "duckdb_temporary_files", "which_secret",
    "pragma_database_size", "pragma_metadata_info", "pragma_storage_info",
    # catalog-wide listings with no grant filter, and listings of a table named at run time
    "duckdb_constraints", "duckdb_dependencies", "duckdb_functions", "duckdb_indexes",
    "duckdb_sequences", "duckdb_table_sample", "duckdb_triggers", "duckdb_types", "pragma_show",
    "pragma_table_info",
    # act on the whole node
    "checkpoint", "disable_logging", "disable_profiling", "enable_logging", "enable_profiling",
    "force_checkpoint", "truncate_duckdb_logs",
    # run SQL or a plan elsewhere, or a table function named by a value
    "create_external_resource", "deregister_external_resource", "destroy_external_resource",
    "json_execute_serialized_sql", "register_external_resource", "register_external_resource_type",
    # cloud credentials and stacks; Iceberg table maintenance by name
    "load_aws_credentials", "remove_iceberg_schema_properties", "remove_iceberg_table_properties",
    "set_iceberg_schema_properties", "set_iceberg_table_properties",
    # lists a directory from SQL text
    "sql_auto_complete",
}

# Whole families, superuser: DuckLake and Iceberg catalog functions (maintenance, raw catalog rows,
# file lists and keys, and the path readers that follow names inside the files), quack's own, raw SQL
# on attached servers and their pools, AWS stacks.
SUPERUSER_TABLE_PREFIXES = ("__aws__", "cloudformation_", "ducklake_", "iceberg_", "mysql_", "postgres_",
                            "quack_", "sqlite_")

# ---- pragmas ------------------------------------------------------------------------------------------

# The parse screen's read-only allowlist (spec Section 3).
PRAGMA_ALLOWED = {"database_list", "functions", "show_databases", "show_tables", "show_tables_expanded",
                  "table_info", "version"}

# Listed for review; any pragma no set names is superuser anyway.
PRAGMA_SUPERUSER = {
    "add_parquet_key", "collations", "copy_database", "database_size", "disable_checkpoint_on_shutdown",
    "disable_object_cache", "disable_optimizer", "disable_print_progress_bar", "disable_profile",
    "disable_profiling", "disable_progress_bar", "disable_verification", "disable_verify_parallelism",
    "enable_checkpoint_on_shutdown", "enable_object_cache", "enable_optimizer", "enable_print_progress_bar",
    "enable_profile", "enable_profiling", "enable_progress_bar", "enable_verification", "extension_versions",
    "force_checkpoint", "import_database", "json_execute_serialized_sql", "metadata_info", "platform",
    "show", "storage_info", "user_agent", "verify_parallelism",
}

# ---- scalar, aggregate and window functions --------------------------------------------------------------

SUPERUSER_SCALAR = {
    "current_setting",          # no argument in a description: any setting, credentials included
    "duckdb_aws_session_id",
    "getenv",                   # the node's environment (a CLI function; listed in case a build has it)
    "index_key",                # reads the index of a table named in a value
    "json_serialize_plan",      # binds the SQL it is given
    "postgres_close_pinned_connection", "postgres_pin_connection",
    "qod_screen",               # qod-node's parse screen, quack's to call
    "setval",                   # writes a sequence named in a value
    "write_log",                # writes the node log
}
# quack's own; the extension families whose scalars may act on attached servers or catalogs.
SCALAR_SUPERUSER_PREFIXES = ("quack_", "postgres_", "ducklake_", "iceberg_")

# A name holding one of these is superuser unless judged and listed in REVIEWED_ALLOWED.
SUSPICIOUS_SUBSTRINGS = ("attach", "aws", "azure", "catalog", "cloud", "config", "connect", "credential",
                         "env", "exec", "extension", "file", "http", "install", "load", "plan", "query",
                         "secret", "setting", "sql", "token", "write")

# Judged harmless although their names hold a suspicious substring or start with a superuser family.
REVIEWED_ALLOWED = {
    "current_connection_id", "current_query", "current_query_id",  # the session's own
    "json_deserialize_sql", "json_serialize_sql",                   # text to and from a parse tree
    "parse_filename",                                               # string manipulation
    # pure value transforms of a superuser family (SCALAR_SUPERUSER_PREFIXES)
    "iceberg_bucket", "iceberg_truncate",                           # Iceberg partition transforms
    "postgres_hstore_get", "postgres_hstore_to_json",               # parse an hstore string
}

# ---- settings -------------------------------------------------------------------------------------------

# Decision 11: changing one for a single session reaches no credential, file, I/O endpoint, resource
# or other session. Allowed at session or local scope only (the pipeline refuses any global scope).
SESSION = {
    # how values read, compare and convert
    "binary_as_string", "calendar", "default_collation", "default_null_order", "default_order",
    "disable_timestamptz_casts", "enable_geoparquet_conversion", "error_on_division_by_zero",
    "ieee_floating_point_ops", "ignore_unknown_crs", "integer_division", "json_geometry_format",
    "lambda_syntax", "legacy_disable_null_type", "old_implicit_casting", "order_by_non_integer_literal",
    "regex_match_operator_semantics", "scalar_subquery_error_on_multiple_rows", "timezone",
    # name resolution (USE arrives as schema)
    "schema", "search_path",
    # this session's own limits and output (not max_expression_depth: it bounds the parser's and the
    # binder's recursion, so raising it lets one session overflow a stack of the shared process)
    "enable_progress_bar", "errors_as_json", "explain_output", "max_execution_time",
    "pivot_filter_threshold", "pivot_limit", "preserve_insertion_order",
    # this session's plans
    "approximate_join_order_threshold", "asof_loop_join_threshold", "delim_join_as_cte",
    "dynamic_or_filter_threshold", "index_scan_max_count", "index_scan_percentage",
    "late_materialization_max_rows", "merge_join_threshold", "nested_loop_join_threshold",
    "ordered_aggregate_threshold", "perfect_ht_threshold", "prefer_range_joins",
    # this session's HTTP retries and timeout (endpoints, proxies and credentials stay superuser)
    "http_retries", "http_retry_backoff", "http_retry_wait_ms", "http_timeout",
}

# ---- generation -------------------------------------------------------------------------------------------

# ---- built-in macros and table macros (Task 19) ----------------------------------------------------

# Allowed functions a macro body may still not call to be allowed: a sequence function reads or
# writes a sequence while the slot is bound, and query() / query_table() take the SQL or the table
# they read as a value, which no screen reads inside a macro.
MACRO_REFUSED_CALLS = {"currval", "nextval", "setval", "query", "query_table"}

# The function types a call can resolve to, by where the body calls it.
CALL_TYPES = {"scalar": ("scalar", "aggregate", "macro"), "window": ("window", "aggregate"),
              "table": ("table", "table_macro")}

# Where an unqualified name resolves: DuckDB's default search path for functions.
CALL_SCHEMAS = ("main", "pg_catalog")

RANK = {"allowed": 0, "listing": 1, "file": 2, "superuser": 3}
TYPES = {"scalar", "aggregate", "window", "table", "pragma"}
MACRO_TYPES = {"macro", "table_macro"}
NAME_RE = re.compile(r"^[a-z_][a-z0-9_]*$")


def fail(message):
    sys.exit(f"classify-qod-node: {message}")


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        fail(f"cannot read {path.relative_to(REPO)}: {e}")


def check_rules():
    named = [("ALLOWED_TABLE", ALLOWED_TABLE), ("LISTING_TABLE", LISTING_TABLE), ("FILE_TABLE", FILE_TABLE),
             ("SUPERUSER_TABLE", SUPERUSER_TABLE)]
    for i, (a, sa) in enumerate(named):
        for b, sb in named[i + 1:]:
            if sa & sb:
                fail(f"{a} and {b} both name {sorted(sa & sb)}")
    prefixed = sorted(n for n in LISTING_TABLE | FILE_TABLE if n.startswith(SUPERUSER_TABLE_PREFIXES))
    if prefixed:
        fail(f"a superuser family prefix covers {prefixed}")
    if PRAGMA_ALLOWED & PRAGMA_SUPERUSER:
        fail(f"PRAGMA_ALLOWED and PRAGMA_SUPERUSER both name {sorted(PRAGMA_ALLOWED & PRAGMA_SUPERUSER)}")
    if SUPERUSER_SCALAR & REVIEWED_ALLOWED:
        fail(f"SUPERUSER_SCALAR and REVIEWED_ALLOWED both name {sorted(SUPERUSER_SCALAR & REVIEWED_ALLOWED)}")
    quack = sorted(n for n in REVIEWED_ALLOWED if n.startswith("quack_"))
    if quack:
        fail(f"REVIEWED_ALLOWED names quack_ functions, which are superuser only: {quack}")


def own_class(name, tpe, review):
    if tpe == "table":
        # Exact names first, so ducklake_set_commit_message is allowed despite its family.
        for cls, names in (("allowed", ALLOWED_TABLE), ("listing", LISTING_TABLE), ("file", FILE_TABLE),
                           ("superuser", SUPERUSER_TABLE)):
            if name in names:
                return cls
        if not name.startswith(SUPERUSER_TABLE_PREFIXES):
            review.append(f"table {name}: superuser (no rule names it)")
        return "superuser"
    if tpe == "pragma":
        if name in PRAGMA_ALLOWED:
            return "allowed"
        if name not in PRAGMA_SUPERUSER:
            review.append(f"pragma {name}: superuser (no rule names it)")
        return "superuser"
    if name in SUPERUSER_SCALAR:
        return "superuser"
    # Before the families, so a reviewed pure member of one is allowed.
    if name in REVIEWED_ALLOWED:
        return "allowed"
    if name.startswith(SCALAR_SUPERUSER_PREFIXES):
        return "superuser"
    hit = next((s for s in SUSPICIOUS_SUBSTRINGS if s in name), None)
    if hit is not None:
        review.append(f"{tpe} {name}: superuser (its name holds '{hit}': judge it, then list it in "
                      f"REVIEWED_ALLOWED or SUPERUSER_SCALAR)")
        return "superuser"
    return "allowed"


def classify_functions(rows, review):
    own, alias_of = {}, {}
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"aliasOf", "name", "schema", "type"}:
            fail(f"functions.json: unexpected element {row!r}")
        name, schema, tpe = row["name"].lower(), row["schema"].lower(), row["type"].lower()
        if tpe not in TYPES:
            fail(f"functions.json: {name} has the unknown type {tpe}")
        if not name or not schema or any(c in name + schema for c in "\t\r\n"):
            fail(f"functions.json: unusable name {row['name']!r} in schema {row['schema']!r}")
        key = (schema, name, tpe)
        cls = own_class(name, tpe, review)
        if own.get(key, cls) != cls:
            fail(f"functions.json: {tpe} {name} is listed twice with different classes")
        own[key] = cls
        if row["aliasOf"] is not None:
            alias_of[key] = (schema, row["aliasOf"].lower(), tpe)
    classes = dict(own)
    for key, target in sorted(alias_of.items()):
        if target in own and RANK[own[target]] > RANK[classes[key]]:
            review.append(f"{key[2]} {key[1]}: {own[target]}, like {target[1]}, which it is an alias of")
            classes[key] = own[target]
    present = {name for (_, name, tpe) in classes if tpe == "table"}
    for rule, names in (("ALLOWED_TABLE", ALLOWED_TABLE), ("LISTING_TABLE", LISTING_TABLE),
                        ("FILE_TABLE", FILE_TABLE)):
        for name in sorted(names - present):
            review.append(f"{rule} names {name}, which functions.json lacks")
    return classes


def body_uses(tree):
    """The calls and table reads in one serialized definition (json_serialize_sql), walking every
    node: a FUNCTION is a scalar call (a table one when it is a TABLE_FUNCTION reference's
    function), a WINDOW a window call, a BASE_TABLE a read. None when DuckDB could not parse it."""
    if not isinstance(tree, dict) or tree.get("error") is not False:
        return None
    calls, reads = set(), False

    def walk(node, position="scalar"):
        nonlocal reads
        if isinstance(node, list):
            for item in node:
                walk(item)
            return
        if not isinstance(node, dict):
            return
        cls, tpe = node.get("class"), node.get("type")
        if cls in ("FUNCTION", "WINDOW") and isinstance(node.get("function_name"), str):
            calls.add(("window" if cls == "WINDOW" else position, (node.get("catalog") or "").lower(),
                       (node.get("schema") or "").lower(), node["function_name"].lower()))
        if tpe == "BASE_TABLE":
            reads = True
        for key, child in node.items():
            walk(child, "table" if tpe == "TABLE_FUNCTION" and key == "function" else "scalar")

    walk(tree.get("statements"))
    return {"calls": [list(c) for c in sorted(calls)], "readsTables": reads}


# A default parameter value a macro body may use and still be allowed: a constant. DuckDB 2.0 folds a
# CREATE MACRO default to a constant (and refuses one it cannot fold), but macros.json must not rest on
# that for a built-in one, whose default the dump does not otherwise carry.
CONSTANT_DEFAULT = re.compile(r"^(-?[0-9]+(\.[0-9]+)?|'([^']|'')*'|NULL|TRUE|FALSE)$", re.IGNORECASE)


def split_arguments(text):
    """The top-level comma-separated parts of a signature's argument list, strings respected."""
    parts, depth, quoted, current = [], 0, False, ""
    for c in text:
        if c == "'":
            quoted = not quoted
        elif not quoted and c in "([":
            depth += 1
        elif not quoted and c in ")]":
            depth -= 1
        if c == "," and depth == 0 and not quoted:
            parts.append(current.strip())
            current = ""
        else:
            current += c
    if current.strip():
        parts.append(current.strip())
    return parts


def macro_defaults(signatures):
    """The default values (as DuckDB renders them) of every overload of a macro, from the binder's
    "Candidate macros:" list in `signatures`, or None when the list is missing (defaults unknown)."""
    if not isinstance(signatures, str) or "Candidate macros:" not in signatures:
        return None
    defaults = []
    for line in signatures.split("Candidate macros:", 1)[1].splitlines():
        line = line.strip()
        if not line:
            continue
        if "(" not in line or not line.endswith(")"):
            return None
        for argument in split_arguments(line[line.index("(") + 1:-1]):
            if ":=" in argument:
                defaults.append(argument.split(":=", 1)[1].strip())
    return sorted(defaults)


def extract_macros(dump):
    """macros.json rows from a dump: one per macro or table macro overload, sorted. A dump row is
    already a macros.json row when it carries `calls` (FunctionClassesSpec classifies the committed
    file this way)."""
    if all(isinstance(row, dict) and "calls" in row for row in dump):
        return dump
    rows = []
    for row in dump:
        if not isinstance(row, dict) or not {"name", "schema", "type", "tree"} <= set(row):
            fail(f"macro dump: unexpected element {row!r}")
        tpe = row["type"].lower()
        if tpe not in MACRO_TYPES:
            fail(f"macro dump: {row['name']} has the type {tpe}, not a macro")
        tree = row["tree"]
        if isinstance(tree, str):
            tree = json.loads(tree)
        uses = body_uses(tree)
        rows.append({"calls": uses["calls"] if uses else None,
                     "defaults": macro_defaults(row.get("signatures")),
                     "name": row["name"].lower(),
                     "readsTables": uses["readsTables"] if uses else None,
                     "schema": row["schema"].lower(), "type": tpe})
    return sorted(rows, key=lambda r: (r["name"], r["schema"], r["type"], json.dumps(r, sort_keys=True)))


def classify_macros(macros, functions, review):
    """The class of each macro (schema, name, type) over every overload, or nothing (unclassified),
    by a least fixpoint: superuser as soon as one call can resolve to a superuser function, allowed
    once every call of every overload resolves only to allowed ones (macros included)."""
    overloads = {}
    for row in macros:
        overloads.setdefault((row["schema"], row["name"], row["type"]), []).append(row)
    classes = {key: None for key in overloads}

    def candidates(call):
        position, catalog, schema, name = call
        if catalog not in ("", "system"):
            return []
        found = []
        for sch in ((schema,) if schema else CALL_SCHEMAS):
            for tpe in CALL_TYPES[position]:
                key = (sch, name, tpe)
                if key in functions:
                    found.append(functions[key])
                elif key in classes:
                    found.append(classes[key] or "pending")
        return found

    changed = True
    while changed:
        changed = False
        for key, rows in overloads.items():
            if classes[key] is not None:
                continue
            calls = [c for r in rows for c in (r["calls"] or [])]
            found = [candidates(c) for c in calls]
            # Every body parsed and reads no table; every default parameter known and constant.
            parsed = all(r["calls"] is not None and not r["readsTables"] and
                         r.get("defaults") is not None and
                         all(CONSTANT_DEFAULT.match(d) for d in r["defaults"]) for r in rows)
            if any("superuser" in f for f in found):
                classes[key] = "superuser"
                changed = True
            elif parsed and not any(c[3] in MACRO_REFUSED_CALLS for c in calls) and \
                    all(f and set(f) == {"allowed"} for f in found):
                classes[key] = "allowed"
                changed = True
    for key, cls in sorted(classes.items()):
        if cls is None:
            review.append(f"{key[2]} {key[0]}.{key[1]}: not classified (its body calls a function not "
                          f"classed allowed, reads a table, or could not be parsed, or a default "
                          f"parameter is unknown or not a constant)")
    return {key: cls for key, cls in classes.items() if cls is not None}


def check_macro_names(macro_classes, functions):
    """The node keys a class by name and type alone (function_classes.cpp) and treats a name classed
    superuser under any type as superuser everywhere, so neither may disagree across a schema or a
    type."""
    by_name_type, by_name = {}, {}
    for (schema, name, tpe), cls in list(functions.items()) + list(macro_classes.items()):
        if by_name_type.setdefault((name, tpe), cls) != cls:
            fail(f"{tpe} {name} has two classes in two schemas")
        by_name.setdefault(name, set()).add(cls)
    mixed = sorted(n for n, cs in by_name.items() if "superuser" in cs and len(cs) > 1)
    if mixed:
        fail(f"names classed superuser under one type and not under another: {mixed}")


def session_capable(row):
    if row["kind"] == "core":
        return row["scope"] in ("local_only", "local_default", "global_default") or (
            row["scope"] == "none" and row["setters"].endswith("l"))
    return row["setters"].endswith("l")


def classify_settings(rows, review):
    settings, aliases = {}, {}
    for row in rows:
        kind = row.get("kind") if isinstance(row, dict) else None
        if kind == "alias":
            aliases[row["name"].lower()] = row["target"].lower()
        elif kind in ("core", "extension"):
            settings[row["name"].lower()] = row
        else:
            fail(f"settings.json: unexpected element {row!r}")
    classes = {}
    for name, row in settings.items():
        if name in SESSION and not session_capable(row):
            fail(f"SESSION names {name}, which cannot be set for one session ({row!r})")
        classes[name] = "session" if name in SESSION else "superuser"
    for name in sorted(SESSION - settings.keys()):
        review.append(f"SESSION names {name}, which settings.json lacks")
    for alias, target in sorted(aliases.items()):
        if target not in classes:
            fail(f"settings.json: the alias {alias} names {target}, which it does not list")
        if alias in settings:
            fail(f"settings.json: {alias} is both a setting and an alias")
        classes[alias] = classes[target]
    return classes


def previous_function_keys():
    if not FUNCTION_TSV.exists():
        return None
    keys = set()
    for line in FUNCTION_TSV.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            cells = line.split("\t")
            keys.add((cells[1], cells[0], cells[2]))
    return keys


def write_lines(path, lines):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(line + "\n" for line in lines), encoding="utf-8")


def table_names(classes, wanted):
    names = sorted({name for (_, name, tpe), cls in classes.items() if tpe == "table" and cls in wanted})
    bad = [n for n in names if not NAME_RE.match(n)]
    if bad:
        fail(f"table function names CMake cannot embed: {bad}")
    return names


def main():
    args = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    args.add_argument("--macros-dump", type=Path,
                      help="the release CLI's dump of the built-in macros; rewrites macros.json from it")
    args.add_argument("--macro-classes", type=Path,
                      help="print the class of each macro of this dump and write nothing")
    opts = args.parse_args()
    check_rules()
    review = []
    functions = classify_functions(load_json(FUNCTIONS_JSON), review)
    if opts.macro_classes:
        classes = classify_macros(extract_macros(load_json(opts.macro_classes)), functions, [])
        for (schema, name, tpe), cls in sorted(classes.items()):
            print(f"{name}\t{schema}\t{tpe}\t{cls}")
        return
    if opts.macros_dump:
        macros = extract_macros(load_json(opts.macros_dump))
        MACROS_JSON.write_text("[\n" + ",\n".join(json.dumps(m, sort_keys=True, separators=(",", ":"))
                                                   for m in macros) + "\n]\n", encoding="utf-8")
    macro_classes = classify_macros(load_json(MACROS_JSON), functions, review)
    check_macro_names(macro_classes, functions)
    settings = classify_settings(load_json(SETTINGS_JSON), review)
    previous = previous_function_keys()
    classified = {**functions, **macro_classes}

    rows = sorted(classified.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2]))
    write_lines(FUNCTION_TSV, [
        "# GENERATED by scripts/dev/classify-qod-node.py from native/qod-node/functions.json and "
        "native/qod-node/macros.json; do not edit.",
        "# name\tschema\ttype\tclass",
    ] + [f"{name}\t{schema}\t{tpe}\t{cls}" for (schema, name, tpe), cls in rows])
    write_lines(SETTING_TSV, [
        "# GENERATED by scripts/dev/classify-qod-node.py from native/qod-node/settings.json; do not edit.",
        "# name\tclass",
    ] + [f"{name}\t{cls}" for name, cls in sorted(settings.items())])
    write_lines(FILE_FUNCTIONS, table_names(functions, {"file"}))
    write_lines(ADMITTED_TABLE_FUNCTIONS, table_names(functions, {"allowed", "listing"}))

    def counts(classes):
        return ", ".join(f"{c} {sum(1 for v in classes.values() if v == c)}"
                         for c in sorted(set(classes.values()), key=lambda c: RANK.get(c, 0)))
    print(f"functions: {len(functions)} classified ({counts(functions)})")
    print(f"macros: {len(macro_classes)} classified ({counts(macro_classes)})")
    print(f"settings: {len(settings)} classified ({counts(settings)})")
    if previous is not None:
        for key in sorted(classified.keys() - previous):
            review.append(f"new: {key[2]} {key[1]} ({classified[key]})")
        for key in sorted(previous - classified.keys()):
            review.append(f"gone: {key[2]} {key[1]}")
    if review:
        print("review:")
        for line in review:
            print(f"  {line}")


if __name__ == "__main__":
    main()
