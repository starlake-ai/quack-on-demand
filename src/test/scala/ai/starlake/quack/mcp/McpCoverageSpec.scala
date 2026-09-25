package ai.starlake.quack.mcp

import ai.starlake.quack.docs.DocEndpoints
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  ActiveStatementHandlers,
  AuditHandlers,
  GroupHandlers,
  MaintenanceHandlers,
  MembershipHandlers,
  NodeHandlers,
  PoolHandlers,
  PoolPermissionHandlers,
  RoleColumnPolicyHandlers,
  RoleHandlers,
  RoleRowPolicyHandlers,
  TagHandlers,
  TenantDbHandlers,
  TenantHandlers,
  UserHandlers
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.telemetry.NoopTelemetryStore
import ai.starlake.quack.model.{PoolKey, RoleDistribution, TenantDbKind}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Drift guard for the full-surface decision (spec 2026-09-10): every REST mutation route
  * (POST/PUT/DELETE/PATCH) must either map to an MCP tool below or sit on the explicit exclusion
  * list. A new mutation endpoint fails this spec until its MCP fate is decided.
  *
  * Blind spot: this guard only walks [[DocEndpoints.all]]; routes a module contributes via
  * `ManagerServer`'s `moduleEndpoints` are not registered there and so sit outside this guard -- a
  * module that adds mutation routes must extend this coverage deliberately.
  */
class McpCoverageSpec extends AnyFlatSpec with Matchers:

  ai.starlake.quack.ondemand.state.testkit.TestPostgres.dropStrayTestDatabases("qodmcpc")

  /** routeKey prefix -> MCP tool name. Prefix matching keeps this robust to path-param template
    * rendering.
    */
  private val covered: List[(String, String)] = List(
    "POST /api/auth/pat/create"              -> "create_pat",
    "POST /api/auth/pat/delete"              -> "delete_pat",
    "POST /api/auth/pat/list"                -> "list_pats",
    "POST /api/auth/pat/revoke"              -> "revoke_pat",
    "POST /api/branch/create"                -> "create_branch",
    "POST /api/branch/discard"               -> "discard",
    "POST /api/branch/propose"               -> "propose_merge",
    "POST /api/catalog/restore"              -> "restore_snapshot",
    "POST /api/catalog/tag/create"           -> "create_tag",
    "POST /api/catalog/tag/delete"           -> "delete_tag",
    "POST /api/catalog/tag/protect"          -> "protect_tag",
    "POST /api/catalog/undrop"               -> "undrop_table",
    "POST /api/database/create"              -> "create_database",
    "POST /api/database/delete"              -> "delete_database",
    "POST /api/database/update"              -> "update_database",
    "POST /api/group/create"                 -> "create_group",
    "POST /api/group/delete"                 -> "delete_group",
    "POST /api/maintenance/policy/delete"    -> "delete_maintenance_policy",
    "POST /api/maintenance/policy/upsert"    -> "upsert_maintenance_policy",
    "POST /api/maintenance/run"              -> "run_maintenance",
    "POST /api/manifest/import"              -> "manifest_import",
    "POST /api/membership/group-role/add"    -> "add_membership",
    "POST /api/membership/group-role/remove" -> "remove_membership",
    "POST /api/membership/user-group/add"    -> "add_membership",
    "POST /api/membership/user-group/remove" -> "remove_membership",
    "POST /api/membership/user-role/add"     -> "add_membership",
    "POST /api/membership/user-role/remove"  -> "remove_membership",
    "POST /api/node/quarantine"              -> "quarantine_node",
    "POST /api/node/restart"                 -> "restart_node",
    "POST /api/node/setMaxConcurrent"        -> "set_node_max_concurrent",
    "POST /api/node/unquarantine"            -> "unquarantine_node",
    "POST /api/pool/create"                  -> "create_pool",
    "POST /api/pool/delete"                  -> "delete_pool",
    "POST /api/pool/permission/grant"        -> "grant_pool_permission",
    "POST /api/pool/permission/revoke"       -> "revoke_pool_permission",
    "POST /api/pool/resume"                  -> "resume_pool",
    "POST /api/pool/scale"                   -> "scale_pool",
    "POST /api/pool/setAutoscale"            -> "set_pool_autoscale",
    "POST /api/pool/setDisabled"             -> "set_pool_disabled",
    "POST /api/pool/setLockdown"             -> "set_pool_lockdown",
    "POST /api/pool/setPodTemplate"          -> "set_pool_pod_template",
    "POST /api/pool/setResources"            -> "set_pool_resources",
    "POST /api/pool/stop"                    -> "stop_pool",
    "POST /api/pool/suspend"                 -> "suspend_pool",
    "POST /api/role/column-policy/create"    -> "create_column_policy",
    "POST /api/role/column-policy/delete"    -> "delete_column_policy",
    "POST /api/role/column-policy/update"    -> "update_column_policy",
    "POST /api/role/create"                  -> "create_role",
    "POST /api/role/delete"                  -> "delete_role",
    "POST /api/role/permission/grant"        -> "grant_role_permission",
    "POST /api/role/permission/revoke"       -> "revoke_role_permission",
    "POST /api/role/row-policy/create"       -> "create_row_policy",
    "POST /api/role/row-policy/delete"       -> "delete_row_policy",
    "POST /api/role/row-policy/update"       -> "update_row_policy",
    "POST /api/statement/kill"               -> "kill_statement",
    "POST /api/tenant/create"                -> "create_tenant",
    "POST /api/tenant/delete"                -> "delete_tenant",
    "POST /api/tenant/setAuth"               -> "set_tenant_auth",
    "POST /api/tenant/setDisabled"           -> "set_tenant_disabled",
    "POST /api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources" ->
      "upsert_federated_source",
    "DELETE /api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets/{name}" ->
      "delete_federated_secret",
    "DELETE /api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}" ->
      "delete_federated_source",
    "PUT /api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets" ->
      "set_federated_secret",
    "POST /api/user/create" -> "create_user",
    "POST /api/user/delete" -> "delete_user",
    "POST /api/user/update" -> "update_user"
  )

  /** Mutation routes deliberately WITHOUT an MCP tool (spec 2026-09-10, Exclusions). */
  private val excludedPrefixes: List[String] = List(
    "POST /api/auth/change-password", // interactive auth flow
    "POST /api/auth/forgot-password",
    "POST /api/auth/login",
    "POST /api/auth/logout",
    "POST /api/auth/reset-password",
    "POST /api/auth/sso/",
    "POST /api/auth/oidc/",
    "POST /api/auth/sql-token/",
    // Branch merge is human-gated by design (Epic 1): the approver must be a different
    // principal than the proposing agent, and no agent tool may perform it.
    "POST /api/branch/merge",
    "POST /api/fleet/", // fleet servers are operator infrastructure, no agent tool
    "POST /api/scim/",  // IdP wire protocol
    "PUT /api/scim/",
    "PATCH /api/scim/",
    "DELETE /api/scim/"
  )

  "every REST mutation route" should "map to an MCP tool or an explicit exclusion" in {
    val mutations = DocEndpoints.all
      .map(DocEndpoints.routeKey)
      .filter(k =>
        k.startsWith("POST ") || k.startsWith("PUT ") ||
          k.startsWith("DELETE ") || k.startsWith("PATCH ")
      )
    val unaccounted = mutations.filterNot(k =>
      covered.exists((prefix, _) => k.startsWith(prefix)) ||
        excludedPrefixes.exists(k.startsWith)
    )
    withClue(
      "REST mutation routes with no MCP tool and no exclusion " +
        "(add a tool or extend McpCoverageSpec deliberately): "
    ) {
      unaccounted shouldBe empty
    }
  }

  // --- Tool-name existence cross-check -------------------------------------------------------
  //
  // Builds the four MCP tool holders exactly the way each holder's own unit spec's Fixture does
  // (cheap in-memory/DuckDB wiring for identity/access/admin; a throwaway migrated Postgres
  // database for platform, since PatStore's Hikari pool fails fast at construction against an
  // unreachable database - same TestPostgres.reachable cancel convention as McpPlatformToolsSpec).
  // McpDataTools is deliberately not built: none of its tool names appear in `covered` (its
  // surface is read-only SQL execution, not a REST mutation route).

  private val Tenant0  = "acme"
  private val TenantDb = "acme_default"

  private def makeDuckDbUserStore(prefix: String): UserStore =
    Class.forName("org.duckdb.DuckDBDriver")
    val tmpFile = java.nio.file.Files.createTempFile(prefix, ".duckdb")
    tmpFile.toFile.delete()
    tmpFile.toFile.deleteOnExit()
    val jdbcUrl = s"jdbc:duckdb:${tmpFile.toAbsolutePath}"
    val c       = java.sql.DriverManager.getConnection(jdbcUrl)
    try
      c.createStatement()
        .execute(
          // Mirrors the real qodstate_user schema (Liquibase 0003 + 0006 + 0022 + 0028 + 0029 +
          // 0030): UserUpsert's ON CONFLICT clause unconditionally touches enabled,
          // must_change_password, email, failed_attempts and locked_at, so all must exist even
          // though this fixture never runs Liquibase against DuckDB.
          """CREATE TABLE IF NOT EXISTS qodstate_user (
          |  id                    TEXT PRIMARY KEY,
          |  tenant                TEXT,
          |  username              TEXT NOT NULL,
          |  password_hash         TEXT NOT NULL,
          |  role                  TEXT NOT NULL DEFAULT 'user',
          |  enabled               BOOLEAN NOT NULL DEFAULT true,
          |  must_change_password  BOOLEAN NOT NULL DEFAULT false,
          |  email                 TEXT,
          |  failed_attempts       INT NOT NULL DEFAULT 0,
          |  locked_at             TIMESTAMPTZ,
          |  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
        )
    finally c.close()
    new UserStore(jdbcUrl, "", "")

  private val noScope: String => Option[SessionScope] = _ => None

  /** identity/access/admin tools, built exactly as McpIdentityToolsSpec / McpAccessToolsSpec /
    * McpAdminToolsSpec's own Fixtures build them.
    */
  private def identityTools: McpIdentityTools =
    val store   = new InMemoryControlPlaneStore()
    val backend = ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.restore()
    val userStore   = makeDuckDbUserStore("qod-mcp-coverage-identity-users")
    val users       = new UserHandlers(sup, userStore)
    val tenants     = new TenantHandlers(sup)
    val groups      = new GroupHandlers(sup, users)
    val roles       = new RoleHandlers(sup, users)
    val memberships = new MembershipHandlers(sup, users)
    new McpIdentityTools(tenants, users, groups, roles, memberships, noScope)

  private def accessTools: McpAccessTools =
    val store   = new InMemoryControlPlaneStore()
    val backend = ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.restore()
    sup.createTenant(Tenant(Tenant0)).unsafeRunSync()
    val userStore       = makeDuckDbUserStore("qod-mcp-coverage-access-users")
    val users           = new UserHandlers(sup, userStore)
    val roleHandlers    = new RoleHandlers(sup, users)
    val columnPolicies  = new RoleColumnPolicyHandlers(sup)
    val rowPolicies     = new RoleRowPolicyHandlers(sup)
    val poolPermissions = new PoolPermissionHandlers(sup, users)
    new McpAccessTools(roleHandlers, columnPolicies, rowPolicies, poolPermissions, noScope)

  private def adminTools: McpAdminTools =
    val store    = new InMemoryControlPlaneStore()
    val backend  = ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop()
    val tracker  = new NodeLoadTracker
    val sup      = new PoolSupervisor(backend, tracker, store)
    val registry = new ai.starlake.quack.edge.ActiveStatementRegistry()
    sup.createTenant(Tenant(Tenant0)).unsafeRunSync()
    store.upsertTenantDb(
      ai.starlake.quack.model.TenantDb(
        id = "td-acme0001",
        tenantId = Tenant0,
        name = TenantDb,
        kind = TenantDbKind.DuckLake,
        metastore = Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "5432",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> TenantDb,
          "schemaName" -> "main"
        ),
        dataPath = "/tmp/qod-mcp-coverage-admin-test"
      )
    )
    sup.restore()
    sup.createPool(PoolKey(Tenant0, TenantDb, "sales"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    val pools = new PoolHandlers(
      sup,
      tracker,
      audit = ai.starlake.quack.ondemand.telemetry.AuditRecorder.noop
    )
    val nodes      = new NodeHandlers(sup, tracker, store, StateChangePublisher.noop)
    val statements = new ActiveStatementHandlers(
      registry,
      new ai.starlake.quack.edge.StatementHistoryStore(),
      store,
      haEnabled = false
    )
    val maintenance = new MaintenanceHandlers(sup, store)
    val tags        = new TagHandlers(
      sup,
      store,
      snapshotExists = (_, _, _) => true,
      snapshotsExist = (_, _, ids) => ids
    )
    val audit     = new AuditHandlers(NoopTelemetryStore)
    val tenantDbs = new TenantDbHandlers(sup, requireEncryption = false)
    new McpAdminTools(pools, nodes, statements, maintenance, tags, audit, tenantDbs, noScope)

  /** Builds a bare McpPlatformTools over a fresh throwaway (migrated) Postgres database: `pats` is
    * a real handler backed by that database (PatStore's Hikari pool fails fast at CONSTRUCTION
    * against an unreachable database, not just at first use, per McpPlatformToolsSpec); federation
    * is left disabled (`federated = None`, per the brief) and everything else is the cheap
    * in-memory/Noop construction the other tiers use. Only `.tools.map(_.name)` is read here, so no
    * seed data (tenants/users/PATs) is needed - this cross-check never calls a tool, it only
    * confirms the name is registered.
    */
  private def withPlatformTools(test: McpPlatformTools => Unit): Unit =
    import ai.starlake.quack.CatalogConfig
    import ai.starlake.quack.ondemand.api.{
      CatalogPreviewHandlers,
      ConfigHandlers,
      ConfigRegistry,
      HistoryHandlers,
      ManifestHandlers,
      PatHandlers,
      SessionTokenStore,
      UsageHandlers
    }
    import ai.starlake.quack.ondemand.state.testkit.TestPostgres
    import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore}
    import com.typesafe.config.ConfigFactory

    import scala.util.Try

    val dbName = s"qodmcpc_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    var pats: PatStore = null
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val patsH = new PatHandlers(pats, new SessionTokenStore(), userOf = (_, _) => None)

      val boom: CatalogPreviewHandlers.PreviewExecutor =
        (_, _, _) => cats.effect.IO.raiseError(new UnsupportedOperationException("not exercised"))
      val reader: (String, String) => ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader =
        (_, _) => throw new UnsupportedOperationException("not exercised")
      val store = new InMemoryControlPlaneStore()
      val sup   = new PoolSupervisor(
        ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop(),
        new NodeLoadTracker,
        store
      )
      val cfg      = CatalogConfig()
      val restoreH = new ai.starlake.quack.ondemand.api.CatalogRestoreHandlers(
        sup,
        store,
        boom,
        boom,
        reader,
        cfg,
        _ => None
      )
      val undropH =
        new ai.starlake.quack.ondemand.api.CatalogUndropHandlers(sup, boom, reader, cfg, _ => None)
      val manifest = new ManifestHandlers(
        store,
        sup,
        managerVersion = "test",
        hostname = "host",
        requireEncryption = false
      )
      val cfgH     = new ConfigHandlers(ConfigFactory.load(), ConfigRegistry.collect(Nil))
      val historyH = new HistoryHandlers(NoopTelemetryStore)
      val usageH   = new UsageHandlers(NoopTelemetryStore)

      val tools = new McpPlatformTools(
        restoreH,
        undropH,
        federated = None,
        manifest,
        patsH,
        cfgH,
        historyH,
        usageH,
        noScope
      )
      test(tools)
    finally
      Try(if pats != null then pats.close())
      Try(TestPostgres.dropDatabase(dbName))

  it should "reference only tool names that are actually registered" in {
    if !ai.starlake.quack.ondemand.state.testkit.TestPostgres.reachable then
      cancel(
        "local Postgres not reachable at " +
          s"${ai.starlake.quack.ondemand.state.testkit.TestPostgres.pgHost}:" +
          s"${ai.starlake.quack.ondemand.state.testkit.TestPostgres.pgPort}; skipping"
      )
    withPlatformTools { platformTools =>
      // Branch tools only touch their handlers inside `run`, so a null handler is enough to
      // enumerate the registered names.
      val branchTools             = new McpBranchTools(null, noScope)
      val registered: Set[String] =
        (identityTools.tools ++ accessTools.tools ++ adminTools.tools ++ platformTools.tools ++
          branchTools.tools)
          .map(_.name)
          .toSet
      val referenced = covered.map(_._2).toSet
      withClue(s"referenced but not registered: ${referenced -- registered}") {
        (referenced -- registered) shouldBe empty
      }
    }
  }
