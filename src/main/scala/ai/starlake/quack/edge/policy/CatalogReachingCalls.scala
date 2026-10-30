package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.sql.{FunctionCalls, StatementHead}

/** Calls the ACL cannot authorize table by table, denied to a tenant-scoped principal whenever the
  * ACL validates (QoD-grant mode and OPA mode alike). Two families:
  *
  *   - Run-time-target table functions: `query('<sql>')`, `query_table('<name>')`,
  *     `json_execute_serialized_sql(...)` and the DuckLake `ducklake_*` family
  *     (`ducklake_table_insertions('<catalog>', '<schema>', '<table>', ...)`, ...), with the
  *     per-schema macros DuckLake resolves in every schema of its catalogs
  *     ([[DuckLakeSchemaMacros]]: `merge_adjacent_files()`, `lake.set_option(...)`, `snapshots()`,
  *     ...), each a call of a `ducklake_*` function on the catalog it is resolved in. The ACL
  *     parser sees only the table function call, never the table it reaches.
  *   - Node-escaping calls that run raw SQL on another server, open a connection to a caller-chosen
  *     target, or read the node's process environment: `postgres_query` / `postgres_execute` (raw
  *     SQL on an attached Postgres with the node's role), `postgres_scan` /
  *     `postgres_scan_pushdown` / `postgres_attach` (connect to any Postgres DSN), the `mysql_*`
  *     and `sqlite_*` equivalents of the mysql / sqlite extensions a node may load, and `getenv`
  *     (reads node env vars, e.g. the catalog `pgPassword`). The SQL or target is a string the ACL
  *     never parses, so per-table grants cannot apply.
  *
  * Owner ruling (2026-10-11): these are NOT refused for every caller. A superuser keeps the raw
  * passthrough (the standard way to send native SQL to a federated source), and with the ACL off
  * and lockdown off nothing is blocked. This gate is the "ACL validates" half; lockdown denies the
  * same family to every non-superuser through [[ai.starlake.quack.edge.sql.LockdownScreen]]. Three
  * consumers call it:
  *
  *   - [[ai.starlake.quack.edge.sql.PostgresAclValidator]], when a tenant-scoped principal is
  *     screened: the `*.*.* ALL` wildcard covers "unsupported constructs", and admitting these
  *     under it would let a tenant admin reach a sibling tenant's catalog, another database, or the
  *     node's secrets.
  *   - the OPA path through [[ai.starlake.quack.edge.sql.AccessGate]], same population.
  *   - [[ProtectedWriteGuard]], for a principal with column or row policies, on every statement
  *     kind (a plain SELECT included): the read would return unfiltered, unmasked rows.
  *
  * Lexical on purpose, not an AST walk: an unparseable statement reaches the wildcard-coverable
  * parse-error arm with no AST at all. Calls are read from DuckDB 2.0's own tokens by
  * [[ai.starlake.quack.edge.sql.FunctionCalls]]: a listed name, bare, double-quoted or
  * backtick-quoted (DuckDB 2.0 runs `` `query_table`('orders') ``), qualified or not, followed past
  * any trivia by an opening parenthesis, compared case-insensitively after unquoting. A name in a
  * comment, or a quoted identifier with no parenthesis after it (a column named `query`), is not a
  * call. String literal bodies are scanned too, so a literal like `'query('` over-denies: the safe
  * direction.
  */
object CatalogReachingCalls:

  /** Table functions whose target is a run-time string the ACL never sees. `duckdb_table_sample`
    * (new in 2.0) reads the table named by its string argument, like `query_table`. The Iceberg
    * catalog functions (listed from the 2.0 CLI with iceberg loaded) rewrite, roll back, migrate or
    * read and change the properties of a table or schema named by a string, outside every table
    * grant; the path-based `iceberg_scan` / `iceberg_metadata` / `iceberg_snapshots` ... are file
    * reads, lockdown's concern.
    */
  private val Fixed = Set(
    "query",
    "query_table",
    "json_execute_serialized_sql",
    "duckdb_table_sample",
    "iceberg_rewrite_data_files",
    "iceberg_rollback_to_snapshot",
    "iceberg_to_ducklake",
    "iceberg_table_properties",
    "iceberg_schema_properties",
    "set_iceberg_table_properties",
    "remove_iceberg_table_properties",
    "set_iceberg_schema_properties",
    "remove_iceberg_schema_properties"
  )

  /** DuckLake's per-schema table macros (`ducklake_default_functions.cpp` of the DuckLake built for
    * the 2.0 pin): `DuckLakeSchemaEntry::LookupEntry` resolves each of these names in every schema
    * of a DuckLake catalog, so `merge_adjacent_files()` under `USE lake`,
    * `lake.set_option('delete_older_than', '1 second')` and `lake.main.snapshots()` all run the
    * `ducklake_*` function of the same name on that catalog (checked on the 2.0 CLI). The bare
    * names escaped the `ducklake_` prefix: a `*.*.* ALL` grant admitted the maintenance and the
    * option writes the explicit calls are refused for. (`merge_adjacent_files` and `set_option`,
    * with the explicit maintenance calls, are also reserved to superusers in every ACL mode by
    * [[ai.starlake.quack.edge.sql.CheckpointScreen.MaintenanceCalls]].)
    *
    * `PRAGMA table_info('t')` is DuckDB's own pragma (it resolves the core pragma, not the macro,
    * on 2.0) and stays admitted: [[isReaching]] skips a `table_info` call right after `PRAGMA`.
    */
  val DuckLakeSchemaMacros: Set[String] = Set(
    "merge_adjacent_files",
    "set_option",
    "set_commit_message",
    "options",
    "settings",
    "current_snapshot",
    "last_committed_snapshot",
    "snapshots",
    "table_info",
    "table_changes",
    "table_deletions",
    "table_insertions"
  )

  /** Calls that run raw SQL elsewhere, open an arbitrary connection, read node env vars, or invoke
    * a table function named at run time. The postgres / quack entries and `getenv` were verified on
    * the 2.0 CLI; the mysql / sqlite entries are the SQL-running and connection-target functions
    * those extensions export, listed from the 1.5.6 CLI (neither extension is in the 2.0
    * toolchain). `mysql_clear_cache`, `mysql_debug_execution_plan` and `mysql_explain_federated`
    * run nothing and open nothing.
    *
    * The `*_external_resource` family (new in 2.0) invokes a table function whose NAME is a value:
    * `destroy_external_resource('read' || '_text', '/x')` ran `read_text` on 2.0, so the name
    * defeats a lexical screen; refusing the invokers themselves closes it, since they cannot be
    * named indirectly. (`list_aggregate` / `aggregate` / `array_aggr` also take a function name as
    * a value, but resolve only aggregate functions, none of which reads a file or runs SQL, so they
    * are not an escape and are not listed.) `postgres_configure_pool` sets `health_check_query`
    * (run on every pool acquire), the function twin of the protected `pg_pool_health_check_query`;
    * `quack_query` / `quack_query_by_name` send raw SQL to a quack endpoint. A DuckDB client's own
    * `quack_query` runs client-side and never reaches the edge as node SQL
    * (QuackCompatibilitySpec), so refusing it in node-bound SQL breaks no federation path.
    */
  private val NodeEscaping = Set(
    "postgres_query",
    "postgres_execute",
    "postgres_scan",
    "postgres_scan_pushdown",
    "postgres_attach",
    "postgres_configure_pool",
    "mysql_query",
    "mysql_execute",
    "sqlite_query",
    "sqlite_scan",
    "sqlite_attach",
    "quack_query",
    "quack_query_by_name",
    "create_external_resource",
    "register_external_resource",
    "register_external_resource_type",
    "deregister_external_resource",
    "destroy_external_resource",
    "getenv"
  )

  /** Settings whose value is SQL (or the name of a function) the node runs later, outside the ACL:
    * the postgres extension runs `pg_staleness_query` / `pg_pool_health_check_query` on the
    * attached catalog Postgres, and the quack server calls the named auth callbacks on every
    * connection. Refused to tenant-scoped principals when the ACL validates (a SET is otherwise
    * control flow and admitted); lockdown already protects all four for every caller.
    */
  val SqlValuedSettings: Set[String] = Set(
    "pg_staleness_query",
    "pg_pool_health_check_query",
    "quack_authentication_function",
    "quack_authorization_function"
  )

  /** The first SQL-valued setting a statement of `sql` sets, resets or pragmas, or None. */
  def sqlValuedSetting(sql: String): Option[String] =
    StatementHead
      .split(sql)
      .iterator
      .flatMap(s => StatementHead.settingAt(StatementHead.of(s)).map(_._1))
      .find(SqlValuedSettings)

  def settingDenyReason(name: String): String =
    s"setting $name runs SQL the gateway cannot authorize; " +
      "not available to tenant-scoped principals (deny, fail-closed)"

  /** The names refused for every caller under lockdown too;
    * [[ai.starlake.quack.edge.sql.LockdownScreen]] reads this so the lockdown and ACL-validates
    * paths can never drift apart. The run-time-target trio is in it: with lockdown on and the ACL
    * off, `query(<computed text>)` ran any reader the lockdown denies by name (a concatenation
    * spells the name), and `query_table('<path>')` read a local file.
    */
  val LockdownNames: Set[String] = NodeEscaping ++ Fixed

  /** Sentinel returned when a statement nests string literals too deeply to screen
    * ([[ai.starlake.quack.edge.sql.FunctionCalls.MaxDepth]]); not a real function name, so it never
    * collides with one.
    */
  private val NestingTooDeep = "\u0000nesting-too-deep"

  /** The first catalog-reaching or node-escaping function called by `sql`, lowercased, or the
    * nesting sentinel when a literal nested past
    * [[ai.starlake.quack.edge.sql.FunctionCalls.MaxDepth]] left part of the statement unscanned
    * (fail closed, as [[ai.starlake.quack.edge.sql.LockdownScreen]] does), or None.
    */
  def find(sql: String): Option[String] =
    val scan = FunctionCalls.scan(sql)
    scan.calls.iterator
      .find(isReaching)
      .map(_.name)
      .orElse(if scan.truncated then Some(NestingTooDeep) else None)

  private def isReaching(call: FunctionCalls.Call): Boolean =
    val name = call.name
    Fixed.contains(name) || NodeEscaping.contains(name) || name.startsWith("ducklake_") ||
    (DuckLakeSchemaMacros.contains(name) && !(name == "table_info" && afterPragma(call)))

  /** True when the significant token before `call`'s name is the word PRAGMA. */
  private def afterPragma(call: FunctionCalls.Call): Boolean =
    val toks = ai.starlake.sql.SqlLexer
      .tokens(call.text)
      .filter(t => !t.isTrivia && t.end <= call.argsFrom)
    // ..., <before>, <name>, `(`
    toks.length >= 3 && {
      val before = toks(toks.length - 3)
      before.kind == ai.starlake.sql.SqlLexer.Kind.Word &&
      call.text.substring(before.start, before.end).equalsIgnoreCase("pragma")
    }

  def denyReason(fn: String): String =
    if fn == NestingTooDeep then
      "statement nests string literals too deeply to screen; " +
        "not available to tenant-scoped principals (deny, fail-closed)"
    else if NodeEscaping.contains(fn) then
      s"$fn reaches data or connections the gateway cannot authorize; " +
        "not available to tenant-scoped principals (deny, fail-closed)"
    else if DuckLakeSchemaMacros.contains(fn) then
      s"$fn is DuckLake's ducklake_$fn on the catalog it resolves in, whose access cannot be " +
        "authorized table by table; not available to tenant-scoped principals (deny, fail-closed)"
    else
      s"table function $fn resolves its target at run time, so its access cannot be authorized; " +
        "not available to tenant-scoped principals (deny, fail-closed)"
