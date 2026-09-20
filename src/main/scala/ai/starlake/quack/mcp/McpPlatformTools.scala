package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.{
  CatalogRestoreHandlers,
  CatalogUndropHandlers,
  ConfigHandlers,
  FederatedSecretUpsertRequest,
  FederatedSourceCreateRequest,
  FederatedSourceHandlers,
  HistoryHandlers,
  ManifestHandlers,
  PatCreateRequest,
  PatDeleteRequest,
  PatHandlers,
  PatRevokeRequest,
  RestoreRequest,
  UndropRequest,
  UsageHandlers
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax._

/** The MCP platform tier: time travel, federation, manifest, PATs, config, and telemetry reads.
  * Full-surface per spec 2026-09-10.
  */
final class McpPlatformTools(
    restoreH: CatalogRestoreHandlers,
    undropH: CatalogUndropHandlers,
    federated: Option[FederatedSourceHandlers], // None when federation is not wired
    manifest: ManifestHandlers,
    pats: PatHandlers,
    serverConfig: ConfigHandlers,
    history: HistoryHandlers,
    usage: UsageHandlers,
    scopeOf: String => Option[SessionScope]
):

  import McpToolArgs._

  def tools: List[McpToolDef] = List(
    restoreSnapshotTool,
    undropTableTool,
    listRecoverableTool,
    listFederatedSourcesTool,
    upsertFederatedSourceTool,
    deleteFederatedSourceTool,
    setFederatedSecretTool,
    deleteFederatedSecretTool,
    manifestExportTool,
    manifestImportTool,
    createPatTool,
    listPatsTool,
    revokePatTool,
    deletePatTool,
    getConfigTool,
    statementHistoryTool,
    usageTrendsTool,
    usageReportTool
  )

  private def keyOf(principal: McpPrincipal): Option[String] = principal.rawToken

  // ---------- restore / undrop ----------

  private val restoreSnapshotTool = McpToolDef(
    name = "restore_snapshot",
    description = "Restore a table to an earlier snapshot or tag ('to' accepts a snapshot " +
      "id or tag name). dry_run=true reports the data diff without writing.",
    inputSchema = objectSchema(
      required = List("database", "schema", "table", "to"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "schema"                    -> strProp("Schema name."),
      "table"                     -> strProp("Table name."),
      "to"                        -> strProp("Target snapshot id or tag name."),
      "dry_run"                   -> boolProp("Report the diff without writing."),
      "expected_current_snapshot" -> intProp(
        "Optimistic-concurrency check: fail if the current snapshot differs."
      ),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        schema   <- required(args, "schema")
        table    <- required(args, "table")
        to       <- required(args, "to")
      yield (tenant, database, schema, table, to)) match
        case Left(err)                                    => IO.pure(Left(err))
        case Right((tenant, database, schema, table, to)) =>
          restoreH
            .restore(
              RestoreRequest(
                tenant = tenant,
                tenantDb = database,
                schema = schema,
                table = table,
                to = to,
                dryRun = bool(args, "dry_run"),
                expectedCurrentSnapshot = long(args, "expected_current_snapshot")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val undropTableTool = McpToolDef(
    name = "undrop_table",
    description = "Recover a dropped table from the last snapshot where it was live " +
      "(see list_recoverable). as_name restores under a different name.",
    inputSchema = objectSchema(
      required = List("database", "schema", "table"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "schema"        -> strProp("Schema the table lived in."),
      "table"         -> strProp("Dropped table name."),
      "as_name"       -> strProp("Restore under this name instead."),
      "from_snapshot" -> intProp("Recover from this snapshot id instead of the last live."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        schema   <- required(args, "schema")
        table    <- required(args, "table")
      yield (tenant, database, schema, table)) match
        case Left(err)                                => IO.pure(Left(err))
        case Right((tenant, database, schema, table)) =>
          undropH
            .undrop(
              UndropRequest(
                tenant = tenant,
                tenantDb = database,
                schema = schema,
                table = table,
                asName = str(args, "as_name"),
                fromSnapshot = long(args, "from_snapshot")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val listRecoverableTool = McpToolDef(
    name = "list_recoverable",
    description = "List dropped tables still recoverable via undrop_table.",
    inputSchema = objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "limit" -> intProp("Max entries."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          undropH
            .recoverable(tenant, database, int(args, "limit"), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- federation ----------

  private def fedTool(
      name: String,
      description: String,
      schema: Json,
      act: (FederatedSourceHandlers, McpPrincipal, JsonObject) => IO[Either[String, Json]]
  ): McpToolDef =
    McpToolDef(
      name = name,
      description = description,
      inputSchema = schema,
      adminOnly = true,
      run = (principal, args) =>
        federated match
          case None =>
            IO.pure(
              Left("federation_disabled: federated sources are not enabled on this manager")
            )
          case Some(h) => act(h, principal, args)
    )

  private val listFederatedSourcesTool = fedTool(
    "list_federated_sources",
    "List a database's federated sources (external systems attached via setup SQL).",
    objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      tenantProp
    ),
    (h, principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          h.listSources(tenant, database).map(res => bridge(res).map(_.asJson))
  )

  private val upsertFederatedSourceTool = fedTool(
    "upsert_federated_source",
    "Create or update a federated source by alias: setup_sql runs at node attach to " +
      "connect the external system. Reference secrets as {{secret_name}}.",
    objectSchema(
      required = List("database", "alias", "setup_sql"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "alias"       -> strProp("Source alias (stable key)."),
      "setup_sql"   -> strProp("ATTACH / CREATE SECRET setup SQL."),
      "description" -> strProp("Optional description."),
      "disabled"    -> boolProp("Create disabled."),
      tenantProp
    ),
    (h, principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        alias    <- required(args, "alias")
        setupSql <- required(args, "setup_sql")
      yield (tenant, database, alias, setupSql)) match
        case Left(err)                                  => IO.pure(Left(err))
        case Right((tenant, database, alias, setupSql)) =>
          h.createSource(
            tenant,
            database,
            FederatedSourceCreateRequest(
              alias = alias,
              setupSql = setupSql,
              description = str(args, "description"),
              disabled = bool(args, "disabled").getOrElse(false)
            ),
            keyOf(principal)
          ).map(res => bridge(res).map(_.asJson))
  )

  private val deleteFederatedSourceTool = fedTool(
    "delete_federated_source",
    "Delete a federated source and its secrets by alias.",
    objectSchema(
      required = List("database", "alias"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "alias" -> strProp("Source alias."),
      tenantProp
    ),
    (h, principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        alias    <- required(args, "alias")
      yield (tenant, database, alias)) match
        case Left(err)                        => IO.pure(Left(err))
        case Right((tenant, database, alias)) =>
          h.deleteSource(tenant, database, alias, keyOf(principal))
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(alias))))
  )

  private val setFederatedSecretTool = fedTool(
    "set_federated_secret",
    "Create or update a secret on a federated source: give value (stored) OR " +
      "external_ref (resolved at attach), not both.",
    objectSchema(
      required = List("database", "alias", "name"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "alias"        -> strProp("Source alias."),
      "name"         -> strProp("Secret name (referenced as {{name}} in setup SQL)."),
      "value"        -> strProp("Secret value to store."),
      "external_ref" -> strProp("External resolver reference."),
      tenantProp
    ),
    (h, principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        alias    <- required(args, "alias")
        name     <- required(args, "name")
      yield (tenant, database, alias, name)) match
        case Left(err)                              => IO.pure(Left(err))
        case Right((tenant, database, alias, name)) =>
          h.upsertSecret(
            tenant,
            database,
            alias,
            FederatedSecretUpsertRequest(
              name = name,
              value = str(args, "value"),
              externalRef = str(args, "external_ref")
            ),
            keyOf(principal)
          ).map(res => bridge(res).map(_.asJson))
  )

  private val deleteFederatedSecretTool = fedTool(
    "delete_federated_secret",
    "Delete a secret from a federated source.",
    objectSchema(
      required = List("database", "alias", "name"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "alias" -> strProp("Source alias."),
      "name"  -> strProp("Secret name."),
      tenantProp
    ),
    (h, principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        alias    <- required(args, "alias")
        name     <- required(args, "name")
      yield (tenant, database, alias, name)) match
        case Left(err)                              => IO.pure(Left(err))
        case Right((tenant, database, alias, name)) =>
          h.deleteSecret(tenant, database, alias, name, keyOf(principal))
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(name))))
  )

  // ---------- manifest ----------

  private val manifestExportTool = McpToolDef(
    name = "manifest_export",
    description = "Export the whole control plane (tenants, dbs, pools, RBAC) as a YAML " +
      "manifest (superuser only). Secrets are redacted.",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) =>
      manifest
        .exportYaml(keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(Json.fromString))
  )

  private val manifestImportTool = McpToolDef(
    name = "manifest_import",
    description = "Apply a YAML manifest to the control plane (superuser only). Returns " +
      "per-kind apply counts.",
    inputSchema = objectSchema(
      required = List("yaml"),
      props = "yaml" -> strProp("Manifest YAML document.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "yaml") match
        case Left(err)   => IO.pure(Left(err))
        case Right(yaml) =>
          manifest
            .importYaml(yaml, keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- PATs ----------

  private val createPatTool = McpToolDef(
    name = "create_pat",
    description = "Mint a personal access token as a child of the calling credential " +
      "(self-scoped: an agent manages only its own token subtree). Scope narrows, never " +
      "widens. The token value is returned ONCE.",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Token label."),
      "expires_at"      -> strProp("ISO-8601 instant, e.g. 2026-12-31T00:00:00Z."),
      "roles"           -> arrayProp("Restrict to these role names."),
      "databases"       -> arrayProp("Restrict to these databases."),
      "pools"           -> arrayProp("Restrict to these pools."),
      "tools"           -> arrayProp("Restrict to these MCP tool names."),
      "verb_ceiling"    -> strProp("Cap table verbs: RO | RW | DDL | ALL."),
      "drop_admin"      -> boolProp("Strip admin from the child token."),
      "stmt_timeout_ms" -> intProp("Per-statement timeout for the child."),
      "max_rows"        -> intProp("Row cap for the child."),
      "branch_only"     -> boolProp(
        "Writes (INSERT/UPDATE/DELETE/DDL) admitted only on a branch, never on the live " +
          "database; reads unchanged. Inherited by every child token."
      )
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "name") match
        case Left(err)   => IO.pure(Left(err))
        case Right(name) =>
          val expires =
            str(args, "expires_at").map(s =>
              scala.util
                .Try(java.time.Instant.parse(s))
                .toEither
                .left
                .map(_ => s"invalid 'expires_at' (want ISO-8601 instant): $s")
            )
          expires match
            case Some(Left(err)) => IO.pure(Left(err))
            case other           =>
              pats
                .create(
                  keyOf(principal),
                  PatCreateRequest(
                    name = name,
                    expiresAt = other.flatMap(_.toOption),
                    roles = strSet(args, "roles"),
                    databases = strSet(args, "databases"),
                    pools = strSet(args, "pools"),
                    tools = strSet(args, "tools"),
                    verbCeiling = str(args, "verb_ceiling"),
                    dropAdmin = bool(args, "drop_admin").getOrElse(false),
                    stmtTimeoutMs = int(args, "stmt_timeout_ms"),
                    maxRows = int(args, "max_rows"),
                    branchOnly = bool(args, "branch_only").getOrElse(false)
                  )
                )
                .map(res => bridge(res).map(_.asJson))
  )

  private val listPatsTool = McpToolDef(
    name = "list_pats",
    description = "List the calling credential's own tokens (a PAT sees its subtree).",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) => pats.list(keyOf(principal)).map(res => bridge(res).map(_.asJson))
  )

  private val revokePatTool = McpToolDef(
    name = "revoke_pat",
    description = "Revoke a token in the caller's subtree; its live statements are killed.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("PAT id (see list_pats).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          pats
            .revoke(keyOf(principal), PatRevokeRequest(id))
            .map(res => bridge(res).map(_.asJson))
  )

  private val deletePatTool = McpToolDef(
    name = "delete_pat",
    description = "Delete a revoked/expired token row from the caller's subtree " +
      "(a live token must be revoked first).",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("PAT id (see list_pats).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          pats
            .delete(keyOf(principal), PatDeleteRequest(id))
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  // ---------- config, telemetry ----------

  private val getConfigTool = McpToolDef(
    name = "get_config",
    description = "The manager's server configuration registry (superuser only). " +
      "Sensitive values render as (set)/(unset).",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) =>
      serverConfig.list(keyOf(principal))(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  private val statementHistoryTool = McpToolDef(
    name = "statement_history",
    description = "Search the statement history: filter by time range, tenant, pool, " +
      "user, status, or free text; paginate with before.",
    inputSchema = objectSchema(
      required = Nil,
      props = "from" -> strProp("ISO-8601 start instant."),
      "to"     -> strProp("ISO-8601 end instant."),
      "pool"   -> strProp("Pool filter."),
      "user"   -> strProp("Username filter."),
      "status" -> strProp("ok | denied | transient | permanent | no-node | no-pool."),
      "q"      -> strProp("Free-text SQL search."),
      "limit"  -> intProp("Max rows."),
      "before" -> strProp("Cursor from a previous page (nextBefore)."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      history
        .statements(
          str(args, "from"),
          str(args, "to"),
          str(args, "tenant"),
          str(args, "pool"),
          str(args, "user"),
          str(args, "status"),
          str(args, "q"),
          int(args, "limit"),
          str(args, "before"),
          keyOf(principal)
        )(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  private val usageTrendsTool = McpToolDef(
    name = "usage_trends",
    description = "Statement throughput/latency rollup buckets over time.",
    inputSchema = objectSchema(
      required = Nil,
      props = "granularity" -> strProp("Bucket granularity, e.g. hour or day."),
      "from" -> strProp("ISO-8601 start instant."),
      "to"   -> strProp("ISO-8601 end instant."),
      "pool" -> strProp("Pool filter."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      history
        .trends(
          str(args, "granularity"),
          str(args, "from"),
          str(args, "to"),
          str(args, "tenant"),
          str(args, "pool"),
          keyOf(principal)
        )(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  private val usageReportTool = McpToolDef(
    name = "usage_report",
    description = "Usage summary grouped by tenant, pool, or user, with per-day breakdown.",
    inputSchema = objectSchema(
      required = Nil,
      props = "from" -> strProp("ISO-8601 start instant."),
      "to"       -> strProp("ISO-8601 end instant."),
      "group_by" -> strProp("tenant | pool | user."),
      "pool"     -> strProp("Pool filter."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      usage
        .usage(
          str(args, "from"),
          str(args, "to"),
          str(args, "group_by"),
          str(args, "tenant"),
          str(args, "pool"),
          keyOf(principal)
        )(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )
