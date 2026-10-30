package ai.starlake.quack.edge.sql

/** Node settings no caller may change, superusers included, whatever the ACL mode, OPA mode and the
  * lockdown flag: those that change how a node lexes, parses or resolves SQL, and those that would
  * print the node's credentials.
  *
  * The edge's classifier, ACL parser and screens read SQL the way DuckDB 2.0 does by default.
  * Changing one of these settings on a node makes the node read later statements differently (under
  * `dialect_compatibility_mode = 'spark'`, `'a\'b'` no longer terminates), and most are GLOBAL, so
  * one session's SET also changes how every other session's statements parse on that node. No
  * caller may hold that switch: a superuser setting it breaks the ACL for everyone on the pool.
  *
  * `shell_history()` prints the node CLI's history, which held the node's init SQL and so its
  * credentials while the spawn scripts fed it through stdin; refused as defense in depth
  * ([[SecretCalls]]).
  *
  * `redact_http_logs = false` writes HTTP header values unredacted into the node's log (verified on
  * 2.0: with an http secret carrying `Authorization: Bearer ...`, a read logs the bearer token in
  * `duckdb_logs`), so node-held credentials (federation and catalog bearer tokens, S3 session
  * tokens) become readable by anyone who can read the log; with ACL off, the default, that is every
  * user. Credentials never round-trip to a gateway caller, superusers included (the control plane
  * redacts them in every response too), and the setting is GLOBAL, so one session's change exposes
  * every session's traffic. `allow_unredacted_secrets` is startup-only on 2.0 and refused here so a
  * later runtime-settable version cannot reopen it.
  *
  * `IMPORT DATABASE 'dir'` and its pragma form `PRAGMA import_database('dir')` (the lowering DuckDB
  * 2.0 itself uses) are refused for every caller: they replay the `schema.sql` / `load.sql` of a
  * directory or URL the edge never reads. Verified on 2.0 that a `SET GLOBAL
  * dialect_compatibility_mode` in `schema.sql` applies, from a local dir or an `http://` URL, so
  * the import would carry any of these settings past this screen. `postgres_query` /
  * `postgres_execute` and the other raw-SQL / arbitrary-connection calls are NOT refused here
  * (owner ruling 2026-10-11: superusers keep the federation passthrough); they are denied to
  * tenant-scoped principals by [[CatalogReachingCalls]] when the ACL validates, and to every
  * non-superuser under [[LockdownScreen]].
  *
  * Settings read from DuckDB 2.0's own tokens, the carried statement of an EXPLAIN / PREPARE
  * wrapper included ([[StatementHead]]); `import_database` is found by [[FunctionCalls]] (any
  * quoting, qualified, inside `query()` literal bodies), and a statement that nests literals past
  * [[FunctionCalls.MaxDepth]] is refused fail-closed. Manager-internal SQL (DuckLake initializer,
  * node bootstrap, demo seed) never goes through the router.
  */
object NodeSettingScreen:

  val RefusalMessage =
    "settings that change how SQL is parsed cannot be changed through the gateway"

  val SecretRefusalMessage =
    "settings that expose node credentials cannot be changed through the gateway"

  val SecretCallRefusalMessage =
    "shell_history() holds the node's init SQL, credentials included, and is disabled"

  val ImportRefusalMessage =
    "IMPORT DATABASE replays SQL the gateway cannot screen and is disabled"

  val NestingRefusalMessage =
    "statement nests string literals too deeply to screen and is disabled"

  /** The DuckDB 2.0 settings that change lexing, parsing, identifier folding or name resolution. */
  val ParserSettings: Set[String] = Set(
    "dialect_compatibility_mode",
    "current_dialect",
    "active_grammar_extensions",
    "allow_parser_override_extension",
    "preserve_identifier_case",
    "show_behavior",
    "table_function_identifier_conversion"
  )

  /** The settings that would print node credentials in clear. */
  val SecretSettings: Set[String] = Set("redact_http_logs", "allow_unredacted_secrets")

  /** Calls that print node credentials. `shell_history()` returns the node CLI's shell history,
    * which records every line the CLI reads from stdin. The spawn scripts used to feed a node's
    * init SQL through stdin, so the history held every line of it: `CREATE SECRET` with the
    * object-store keys, the `ATTACH` carrying the catalog password, `quack_serve` with the node
    * token (a quack client's `SELECT * FROM shell_history()` printed them on the 2.0 CLI). They now
    * hand it over through `-init` with stdin carrying nothing, which the history does not record,
    * so the refusal is defense in depth (a launcher that feeds stdin again would reopen it). It
    * takes no argument, so no path-based list ever judged it.
    */
  val SecretCalls: Set[String] = Set("shell_history")

  /** Every setting this screen refuses. */
  val Settings: Set[String] = ParserSettings ++ SecretSettings

  // ---- node-wide settings: superuser-only ----

  /** Settings that act on the whole shared node in EVERY scope, refused by [[screenNodeWide]] to
    * every caller who is not a superuser (whatever the ACL mode and the grants: a setting is not a
    * table) and to every caller on a pool under lockdown, `SET SESSION` included. One session
    * changing them starves or slows every other session of the pool, changes how the node stores or
    * checkpoints data, or turns on logging and profiling that capture other sessions' statements.
    * Operators set them at node start (database / pool `initSql`, `qod pool set-resources`), which
    * never goes through the router.
    *
    * Built from the DuckDB 2.0 CLI (`duckdb_settings()` with httpfs, postgres, ducklake and quack
    * loaded): every setting 2.0 refuses at session scope ("can only be set globally" / "cannot be
    * set locally"), plus the ones it accepts per session but that act on shared machinery (the
    * allocator, WAL checkpointing, the postgres and mysql connection pools, profiling and its
    * output), plus the PRAGMA names that toggle the same machinery. [[NodeWidePrefixes]] covers
    * every `quack_*` (server limits, timeouts, `quack_debug_*` fault injection), `debug_*` and
    * `verify_*` setting. Excludes what [[Settings]] already refuses for everyone.
    *
    * This list is the stricter of two rules. The other is scope: a bare `SET x` / `RESET x` /
    * `PRAGMA x = v` lands where DuckDB 2.0 registered `x` ([[DuckDbSettingScopes]]), which for a
    * GLOBAL-default setting is the shared DBConfig, exactly like `SET GLOBAL`. So a bare statement
    * of a GLOBAL-default setting (default_order, null_order, default_collation,
    * preserve_insertion_order, enable_optimizer, extra_http_headers, the ducklake_* options, ...)
    * is refused too, while its `SET SESSION` stays admitted; a name the table does not hold is
    * refused (fail closed: an extension loaded later can register a GLOBAL option). Session-scoped
    * settings a client legitimately sends (search_path, schema, TimeZone, Calendar, http_timeout,
    * enable_progress_bar, errors_as_json, max_execution_time, s3_* per-session credentials, ...)
    * and `SET VARIABLE` stay admitted bare, except at GLOBAL scope (see [[GlobalScopeRefusal]]).
    */
  val NodeWideSettings: Set[String] = Set(
    // Threads and scheduling.
    "threads",
    "worker_threads",
    "external_threads",
    "async_threads",
    "pin_threads",
    "scheduler_process_partial",
    // Memory, temp space and the allocator.
    "memory_limit",
    "max_memory",
    "max_temp_directory_size",
    "temp_directory",
    "temp_file_encryption",
    "block_allocator_memory",
    "allocator_flush_threshold",
    "allocator_bulk_deallocation_flush_threshold",
    "allocator_background_threads",
    "write_buffer_row_group_memory_limit",
    // Storage, checkpoint, WAL and buffer manager.
    "access_mode",
    "checkpoint_threshold",
    "wal_autocheckpoint",
    "wal_autocheckpoint_entries",
    "auto_checkpoint_skip_wal_threshold",
    "checkpoint_on_detach",
    "fsync_mode",
    "storage_compatibility_version",
    "default_block_size",
    "standard_vector_size",
    "storage_block_prefetch",
    "max_vacuum_tasks",
    "force_compression",
    "disabled_compression_methods",
    "zstd_min_string_length",
    "enable_fsst_vectors",
    "experimental_metadata_reuse",
    "force_column_metadata_reuse",
    "variant_minimum_shredding_size",
    "geometry_minimum_shredding_size",
    "disabled_optimizers",
    "warnings_as_errors",
    "current_transaction_invalidation_policy",
    "disable_database_invalidation",
    "duckdb_api",
    "custom_user_agent",
    // Caches and the HTTP client shared by every session.
    "cache_local_files",
    "enable_external_file_cache",
    "external_file_cache_local_max_block_size",
    "external_file_cache_remote_max_block_size",
    "external_file_cache_remote_min_block_size",
    "external_file_cache_request_sizing",
    "external_file_cache_spill",
    "http_client_pool_capacity",
    "httpfs_client_implementation",
    "httpfs_connection_caching",
    "http_proxy",
    "http_proxy_username",
    "http_proxy_password",
    "s3_url_scheme_aliases",
    // The catalog Postgres connection pool.
    "pg_connection_limit",
    "pg_connection_cache",
    "pg_pool_max_connections",
    "pg_pool_acquire_mode",
    "pg_pool_enable_reaper_thread",
    "pg_pool_enable_thread_local_cache",
    "pg_pool_idle_timeout_millis",
    "pg_pool_max_lifetime_millis",
    "pg_pool_wait_timeout_millis",
    "pg_debug_show_queries",
    // The mysql connection pool, and its query printer (a process-wide flag: every session's
    // queries to the node's stdout).
    "mysql_pool_acquire_mode",
    "mysql_pool_connection_idle_timeout_millis",
    "mysql_pool_connection_max_lifetime_millis",
    "mysql_pool_enable_reaper_thread",
    "mysql_pool_enable_thread_local_cache",
    "mysql_pool_health_check_query",
    "mysql_pool_size",
    "mysql_pool_wait_timeout_millis",
    "mysql_debug_show_queries",
    // Extension loading and security posture (also lockdown-protected).
    "allow_community_extensions",
    "allow_extension_repositories",
    "allow_extensions_metadata_mismatch",
    "allow_unsigned_extensions",
    "autoinstall_extension_repository",
    "autoinstall_known_extensions",
    "autoload_known_extensions",
    "custom_extension_repository",
    "extension_directories",
    "extension_directory", // the deprecated 2.0 alias, still accepted
    "extension_repository_directory",
    "enable_external_access",
    "lock_configuration",
    "allowed_configs",
    "allowed_directories",
    "allowed_paths",
    "disabled_filesystems",
    "allow_persistent_secrets",
    "default_secret_storage",
    "secret_directory",
    // Path resolution: `file_search_path` makes a relative name resolve in any directory
    // (`SET file_search_path = '/etc'; SELECT * FROM x.csv` read /etc/x.csv on 2.0),
    // `home_directory` moves `~`, `allow_asterisks_in_http_paths` turns `*` in a URL into a glob.
    "file_search_path",
    "home_directory",
    "allow_asterisks_in_http_paths",
    // TLS trust for every server the node reads from.
    "ca_cert_file",
    "enable_server_cert_verification",
    "enable_curl_server_cert_verification",
    // Logging and profiling: capture other sessions' statements, or write files.
    "enable_logging",
    "logging_level",
    "logging_mode",
    "logging_storage",
    "enabled_log_types",
    "disabled_log_types",
    "log_query_path",
    "enable_profiling",
    "profiling_output",
    "profile_output",
    "profiling_coverage",
    "profiling_renderer_settings",
    "tracked_metrics",
    // PRAGMA toggles of the same machinery.
    "enable_profile",
    "disable_profile",
    "disable_profiling",
    "enable_checkpoint_on_shutdown",
    "disable_checkpoint_on_shutdown",
    "enable_object_cache",
    "disable_object_cache",
    "force_checkpoint"
  ) -- Settings

  /** Every `quack_*` server setting (limits, timeouts, buffers, `quack_debug_*` fault injection),
    * and every `debug_*` / `verify_*` setting or pragma, present and future.
    */
  val NodeWidePrefixes: Seq[String] = Seq("quack_", "debug_", "verify_", "disable_verify_")

  /** `name` or the setting it is a DuckDB alias of (`force_mbedtls_unsafe` is
    * `debug_force_mbedtls_unsafe`, `null_order` is `default_null_order`) is in [[NodeWideSettings]]
    * or under [[NodeWidePrefixes]].
    */
  def isNodeWide(name: String): Boolean =
    Seq(name, canonical(name)).exists(n =>
      NodeWideSettings(n) || NodeWidePrefixes.exists(n.startsWith)
    )

  /** The lowercased setting DuckDB 2.0 resolves `name` (lowercased) to: a core alias maps to the
    * setting it names, anything else to itself.
    */
  def canonical(name: String): String = DuckDbSettingScopes.Aliases.getOrElse(name, name)

  /** Calls that act on the node itself rather than on data: start / stop / cancel its quack server
    * or rename it, list the other sessions it serves, clear the shared caches, turn the logger or
    * the profiler on or off, truncate the shared log, and FORCE CHECKPOINT (aborts every other
    * session's transaction).
    *
    * `quack_active_connections()` returns every session of the node's quack server with its
    * connection id, client id hash, state and the text of its running query (checked on the 2.0
    * CLI), so one tenant's session reads another's SQL, literals included, and learns the ids
    * `quack_cancel` takes. `quack_server_list()` lists the node's servers with their listen
    * address, their count of open connections and their info map. Neither has a use in a data
    * session; operators read them on the node.
    */
  val NodeWideCalls: Set[String] = Set(
    "quack_serve",
    "quack_stop",
    "quack_cancel",
    "quack_identify",
    "quack_active_connections",
    "quack_server_list",
    "quack_clear_cache",
    "pg_clear_cache",
    "mysql_clear_cache",
    "enable_logging",
    "disable_logging",
    "truncate_duckdb_logs",
    "enable_profiling",
    "disable_profiling",
    "force_checkpoint",
    // tpch's generator: creates and fills TPC-H tables in whatever catalog its argument names, at
    // any scale factor (the node's disk and memory)
    "dbgen"
  )

  val NodeWideRefusal =
    "settings that act on the whole node are reserved to superusers"

  val GlobalScopeRefusal =
    "SET GLOBAL / RESET GLOBAL changes every session on the node and is reserved to superusers"

  /** A bare SET / RESET / `PRAGMA name = value` of a setting DuckDB registers as GLOBAL-default. */
  def unscopedRefusal(name: String): String =
    s"$name without a scope changes it for every session on the node, which is reserved to " +
      s"superusers; use SET SESSION $name (RESET SESSION $name) to change it for this session"

  /** A setting [[DuckDbSettingScopes]] does not hold, so its scope is unknown. */
  def unknownSettingRefusal(name: String): String =
    s"setting $name is not one the gateway knows the scope of; changing it is reserved to " +
      "superusers"

  val UnreadableSettingRefusal =
    "the setting this statement changes cannot be read; changing it is reserved to superusers"

  def nodeWideCallRefusal(fn: String): String =
    s"$fn acts on the whole node and is reserved to superusers"

  /** The refusal for the first statement of `sql` that sets, resets or pragmas a node-wide setting
    * ([[isNodeWide]]) in any form, uses GLOBAL scope for any setting, changes a GLOBAL-default
    * setting without a scope or a setting of unknown scope ([[settingRefusal]]), runs FORCE
    * CHECKPOINT, or calls one of [[NodeWideCalls]] anywhere (literal bodies included). Read like
    * [[screen]]: DuckDB 2.0 tokens, glued quotes, the statement an EXPLAIN / PREPARE carries.
    */
  def screenNodeWide(sql: String): Option[String] =
    LockdownScreen
      .splitStatements(sql)
      .iterator
      .flatMap(s => nodeWideOne(StatementHead.of(s)))
      .nextOption()
      .orElse(
        FunctionCalls
          .scan(sql)
          .calls
          .find(c => NodeWideCalls(c.name))
          .map(c => nodeWideCallRefusal(c.name))
      )

  private def nodeWideOne(h: StatementHead.Head): Option[String] =
    val i = h.start
    if h.word(i).contains("force") && h.word(i + 1).contains("checkpoint") then
      Some(NodeWideRefusal)
    else if installsCode(h, i) then Some(ExtensionRefusal)
    else settingStatement(h).flatMap(settingRefusal)

  /** How a setting statement picks the scope it writes. */
  private enum Form:
    /** `SET x`, `RESET x`, `SET TIME ZONE`, `PRAGMA x = v` (2.0 lowers it to an unscoped SET): the
      * scope DuckDB registered for `x` (`PhysicalSet::GetSettingScope`).
      */
    case Unscoped

    /** `SET SESSION`, `RESET SESSION`, and `SET LOCAL` / `RESET LOCAL` (not implemented on 2.0). */
    case Session

    case Global

    /** `PRAGMA x` / `PRAGMA x(...)`: calls the pragma function `x`, never assigns a setting (2.0
      * fails with "Pragma Function with name x does not exist" for a setting name).
      */
    case PragmaCall

  /** The setting a SET / RESET / PRAGMA statement targets (None when its name cannot be read) and
    * its [[Form]]; None for any other statement and for `SET VARIABLE` / `RESET VARIABLE`, which
    * set a client variable, not a setting. Read like [[settingAt]].
    */
  private def settingStatement(h: StatementHead.Head): Option[(Option[String], Form)] =
    val i = h.start
    h.word(i) match
      case Some("set" | "reset") =>
        val scope = h.word(i + 1).filter(_ => h.identifier(i + 2).isDefined)
        if scope.contains("variable") then None
        else if h.word(i).contains("set") && h.word(i + 1).contains("time") &&
          h.word(i + 2).contains("zone")
        then Some(Some("timezone") -> Form.Unscoped)
        else
          val form = scope match
            case Some("global")            => Form.Global
            case Some("session" | "local") => Form.Session
            case _                         => Form.Unscoped
          Some(settingAt(h).map(_._1) -> form)
      case Some("pragma") =>
        settingAt(h) match
          case Some((name, at)) =>
            // `PRAGMA table_info = 't'` is SQLite's spelling of the call, kept as one on 2.0.
            val assigns = at + 1 < h.toks.length &&
              h.view.substring(h.toks(at + 1).start, h.toks(at + 1).end).startsWith("=")
            Some(
              Some(name) -> (if assigns && name != "table_info" then Form.Unscoped
                             else Form.PragmaCall)
            )
          case None => Some(None -> Form.PragmaCall)
      case _ => None

  /** GLOBAL scope is refused for any setting, and [[isNodeWide]] in every form. Otherwise the scope
    * DuckDB 2.0 registered decides ([[DuckDbSettingScopes]]): GLOBAL-only is refused, a
    * GLOBAL-default setting is refused unscoped and admitted with `SET SESSION`, a session-default
    * one is admitted, and a name the table does not hold is refused (fail closed). A pragma call
    * assigns nothing and is refused only for the node-wide pragmas.
    */
  private def settingRefusal(target: Option[String], form: Form): Option[String] =
    target match
      case None => Option.when(form != Form.PragmaCall)(UnreadableSettingRefusal)
      case Some(_) if form == Form.Global     => Some(GlobalScopeRefusal)
      case Some(name) if isNodeWide(name)     => Some(NodeWideRefusal)
      case Some(_) if form == Form.PragmaCall => None
      case Some(name)                         =>
        DuckDbSettingScopes.Settings.get(canonical(name)).map(_.scope) match
          case None                                          => Some(unknownSettingRefusal(name))
          case Some(DuckDbSettingScopes.Scope.GlobalOnly)    => Some(NodeWideRefusal)
          case Some(DuckDbSettingScopes.Scope.GlobalDefault) =>
            Option.when(form == Form.Unscoped)(unscopedRefusal(name))
          case Some(_) => None

  /** `INSTALL` (any form: it downloads extension code onto the shared node, from the default
    * repository too), `FORCE INSTALL`, `UPDATE EXTENSIONS`, and a `LOAD` naming a path or URL (code
    * read from a file). A bare `LOAD <name>` of an extension the node already has stays open.
    */
  private def installsCode(h: StatementHead.Head, i: Int): Boolean =
    h.word(i) match
      case Some("install") => true
      case Some("force")   => h.word(i + 1).contains("install")
      case Some("update")  => h.word(i + 1).contains("extensions")
      case Some("load")    =>
        (i + 1 until h.toks.length).exists(k =>
          h.toks(k).kind == ai.starlake.sql.SqlLexer.Kind.Literal
        )
      case _ => false

  val ExtensionRefusal =
    "installing an extension, or loading one from a path or URL, is reserved to superusers; " +
      "LOAD an installed extension by its bare name"

  /** The refusal for the first statement of `sql` that changes one of [[Settings]], or imports a
    * database (the `IMPORT DATABASE` statement or the `import_database` pragma), if any. A
    * statement that nests string literals past [[FunctionCalls.MaxDepth]] is refused fail-closed,
    * since a call left unscanned there could be an `import_database`.
    */
  def screen(sql: String): Option[String] =
    LockdownScreen
      .splitStatements(sql)
      .iterator
      .flatMap(screenOne)
      .nextOption()
      .orElse {
        val scan = FunctionCalls.scan(sql)
        if scan.calls.exists(_.name == "import_database") then Some(ImportRefusalMessage)
        else if scan.calls.exists(c => SecretCalls(c.name)) then Some(SecretCallRefusalMessage)
        else if scan.truncated then Some(NestingRefusalMessage)
        else None
      }

  /** Read from DuckDB 2.0's own tokens, never from a regex over the text: the tokenizer needs no
    * whitespace between a keyword and a quoted name, so `SET"show_behavior"='setting'`,
    * ``SET GLOBAL`show_behavior`=...`` and `PRAGMA"show_behavior"=...` all apply on 2.0, and a
    * comment between two tokens separates them like a space. The statement is the one an EXPLAIN
    * carries, if any ([[StatementHead]]): `EXPLAIN ANALYZE SET ...` applies the setting, while
    * `EXPLAIN UPDATE t SET show_behavior = 1` names a column.
    */
  private def screenOne(stmt: String): Option[String] =
    val h = StatementHead.of(stmt)
    if h.word(h.start).contains("import") && h.word(h.start + 1).contains("database") then
      Some(ImportRefusalMessage)
    else
      settingTarget(h).map(canonical).collect {
        case n if ParserSettings(n) => RefusalMessage
        case n if SecretSettings(n) => SecretRefusalMessage
      }

  /** The setting each statement of `sql` sets, resets or pragmas, lowercased and unquoted, read the
    * same way this screen reads it (EXPLAIN / PREPARE carried statement, glued quotes, scope
    * keywords). Shared with [[CatalogReachingCalls]], which refuses the SQL-valued settings to
    * tenant-scoped principals.
    */
  def targetedSettings(sql: String): Iterator[String] =
    LockdownScreen.splitStatements(sql).iterator.flatMap(s => settingTarget(StatementHead.of(s)))

  /** SET [SESSION | GLOBAL | LOCAL] <name>, RESET ..., PRAGMA <name>: the name, if the carried
    * statement is one of those. A scope word followed by a name is a scope.
    */
  private def settingTarget(h: StatementHead.Head): Option[String] = settingAt(h).map(_._1)

  /** [[StatementHead.settingAt]], under the name this screen's callers use. */
  private[sql] def settingAt(h: StatementHead.Head): Option[(String, Int)] =
    StatementHead.settingAt(h)
