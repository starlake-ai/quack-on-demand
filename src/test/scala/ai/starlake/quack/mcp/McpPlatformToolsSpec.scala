package ai.starlake.quack.mcp

import ai.starlake.quack.{CatalogConfig, FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.config.{AclConfig, AuthenticationConfig, ValidationConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CatalogPreviewHandlers,
  CatalogRestoreHandlers,
  CatalogUndropHandlers,
  ConfigHandlers,
  ConfigRegistry,
  FederatedSourceHandlers,
  HistoryHandlers,
  ManifestHandlers,
  PatHandlers,
  SessionTokenStore,
  UsageHandlers
}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  LiquibaseRunner,
  PatStore,
  RbacUser,
  UserStore
}
import ai.starlake.quack.ondemand.telemetry.NoopTelemetryStore
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Platform-tier MCP tool contract, excluding the heavy restore/undrop fixture (see
  * [[McpPlatformCatalogToolsSpec]]): federation round-trip and its `federated = None` disabled arm,
  * manifest export, self-scoped PATs, the config registry, and the telemetry-read smoke tests.
  * Every test spins up a throwaway migrated Postgres database (cancelled if unreachable, same
  * convention as [[ai.starlake.quack.ondemand.api.PatRevokeKillSpec]] and
  * [[ai.starlake.quack.ondemand.api.FederatedSourceHandlersSpec]]): `PatHandlers` needs a real
  * `PatStore` even for tools this spec never calls, because `PatStore`'s Hikari pool fails fast at
  * CONSTRUCTION against an unreachable database, not just at first use.
  */
class McpPlatformToolsSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodmcpp")

  private val Tenant0  = "acme"
  private val TenantDb = "acme_default"
  private val patToken = "qod_pat_alice"

  private def stubBackend = StubQuackBackend.noop()

  /** Never-exercised restore/undrop handlers: this spec's tools list needs them to satisfy
    * McpPlatformTools' constructor, but no test here calls restore_snapshot / undrop_table /
    * list_recoverable (see McpPlatformCatalogToolsSpec for those), so a reader/executor that throws
    * if ever invoked is a stronger guarantee than a working stub would be.
    */
  private def unexercisedCatalogHandlers(
      store: InMemoryControlPlaneStore,
      sup: PoolSupervisor
  ): (CatalogRestoreHandlers, CatalogUndropHandlers) =
    val boom: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _) => cats.effect.IO.raiseError(new UnsupportedOperationException("not exercised"))
    val reader: (String, String) => ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader =
      (_, _) => throw new UnsupportedOperationException("not exercised")
    val cfg      = CatalogConfig()
    val restoreH =
      new CatalogRestoreHandlers(sup, store, boom, boom, reader, cfg, _ => None)
    val undropH = new CatalogUndropHandlers(sup, boom, reader, cfg, _ => None)
    (restoreH, undropH)

  private def liveConfigEntries = ConfigRegistry.collect(
    ConfigRegistry.rootsFor(
      managerCls = classOf[ManagerConfig],
      flightCls = classOf[FlightConfig],
      authCls = classOf[AuthenticationConfig],
      aclCls = classOf[AclConfig],
      validationCls = classOf[ValidationConfig],
      metricsCls = classOf[MetricsConfig]
    )
  )

  /** Builds every McpPlatformTools dependency over a fresh throwaway (migrated) Postgres database:
    * `pats` and, when `withFederation` is true, `federated` are real handlers backed by that
    * database; everything else is the cheap in-memory/Noop construction the other tool tiers' specs
    * already use. Cancels the test when local Postgres is unreachable.
    */
  private def withTools(withFederation: Boolean = false)(
      test: (McpPlatformTools, String) => Unit
  ): Unit =
    if !TestPostgres.reachable then
      cancel(
        s"local Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}; skipping"
      )
    val dbName = s"qodmcpp_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    var users: UserStore = null
    var pats: PatStore   = null
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      users.upsertUser(None, "alice", "pw", "admin")
      val uid          = users.userIdOf(None, "alice").get
      val (_, rootRaw) =
        pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)

      val patsH = new PatHandlers(pats, new SessionTokenStore(), userOf = (_, _) => None)

      // Shared by McpPlatformTools' own scopeOf and FederatedSourceHandlers' own scopeOf below,
      // so the PAT-aware gates (SuperuserCheck, TenantScopeCheck) inside FederatedSourceHandlers
      // see the same principal MCP resolved.
      val scopeOf: String => Option[SessionScope] =
        t =>
          if t == patToken then
            Some(SessionScope(superuser = false, manageableTenants = Set(Tenant0)))
          else None

      val federated =
        if withFederation then
          // qodstate_federated_source.tenant_db_id FK-references a real qodstate_tenant_db row,
          // so the resolver needs an actually-seeded tenant/tenant-db, not a made-up id (lifted
          // from FederatedSourceHandlersSpec.withEnv).
          val cp = new ai.starlake.quack.ondemand.state.PostgresControlPlaneStore(
            url,
            TestPostgres.pgUser,
            TestPostgres.pgPass
          )
          cp.upsertTenant(
            ai.starlake.quack.model.Tenant(id = Tenant0, displayName = Tenant0, disabled = false)
          )
          cp.upsertTenantDb(
            ai.starlake.quack.model.TenantDb(
              id = "td-1",
              tenantId = Tenant0,
              name = TenantDb,
              kind = ai.starlake.quack.model.TenantDbKind.InMemory,
              metastore = Map.empty,
              dataPath = ""
            )
          )
          val fedStore = new ai.starlake.quack.ondemand.state.FederatedSourceStore(
            url,
            TestPostgres.pgUser,
            TestPostgres.pgPass
          )
          val resolver: (String, String) => Option[String] =
            (t, d) =>
              cp.listTenants()
                .find(_.id == t)
                .flatMap(tt => cp.listTenantDbs(tt.id).find(_.name == d).map(_.id))
          Some(
            new FederatedSourceHandlers(
              fedStore,
              resolver,
              scopeOf = scopeOf,
              catalogAliasOf = _ => None
            )
          )
        else None

      val store               = new InMemoryControlPlaneStore()
      val sup                 = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
      val (restoreH, undropH) = unexercisedCatalogHandlers(store, sup)
      val manifest            = new ManifestHandlers(
        store,
        sup,
        managerVersion = "test",
        hostname = "host",
        requireEncryption = false
      )
      val cfgH     = new ConfigHandlers(ConfigFactory.load(), liveConfigEntries)
      val historyH = new HistoryHandlers(NoopTelemetryStore)
      val usageH   = new UsageHandlers(NoopTelemetryStore)

      val tools =
        new McpPlatformTools(
          restoreH,
          undropH,
          federated,
          manifest,
          patsH,
          cfgH,
          historyH,
          usageH,
          scopeOf
        )

      test(tools, rootRaw)
    finally
      Try(if pats != null then pats.close())
      Try(if users != null then users.close())
      Try(TestPostgres.dropDatabase(dbName))

  private def adminPat(raw: String): McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u1", tenant = None, username = "alice", role = "admin"),
        patId = "pat-1",
        scope = SessionScope(superuser = false, manageableTenants = Set(Tenant0)),
        isAdmin = true,
        restriction = TokenRestriction.Unrestricted
      ),
      raw
    )

  /** A tenant-scoped admin PAT whose raw bearer is `patToken`: the fixture's own `scopeOf` (shared
    * by McpPlatformTools' gates and FederatedSourceHandlers' own `scopeOf`) resolves it to a
    * non-superuser scope confined to [[Tenant0]] -- that server-side resolution, not the `scope`
    * field on the [[PatPrincipal]] below, is what the security gates under test consult. Used by
    * the federated-source security-gate regression tests below.
    */
  private def tenantPat: McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u2", tenant = None, username = "bob", role = "admin"),
        patId = "pat-2",
        scope = SessionScope(superuser = false, manageableTenants = Set(Tenant0)),
        isAdmin = true,
        restriction = TokenRestriction.Unrestricted
      ),
      patToken
    )

  private def call(
      tools: McpPlatformTools,
      name: String,
      principal: McpPrincipal,
      args: (String, Json)*
  ): Either[String, Json] =
    val tool = tools.tools.find(_.name == name).getOrElse(fail(s"tool $name not defined"))
    tool.adminOnly shouldBe true
    tool.run(principal, JsonObject(args*)).unsafeRunSync()

  "upsert_federated_source and secrets" should "round-trip a source with a secret" in
    withTools(withFederation = true) { (tools, _) =>
      val created = call(
        tools,
        "upsert_federated_source",
        McpPrincipal.StaticKey,
        "tenant"    -> Json.fromString(Tenant0),
        "database"  -> Json.fromString(TenantDb),
        "alias"     -> Json.fromString("fedpg"),
        "setup_sql" -> Json.fromString("INSTALL postgres;")
      )
      withClue(created)(created.isRight shouldBe true)

      val secreted = call(
        tools,
        "set_federated_secret",
        McpPrincipal.StaticKey,
        "tenant"   -> Json.fromString(Tenant0),
        "database" -> Json.fromString(TenantDb),
        "alias"    -> Json.fromString("fedpg"),
        "name"     -> Json.fromString("PG_PASSWORD"),
        "value"    -> Json.fromString("hunter2")
      )
      withClue(secreted)(secreted.isRight shouldBe true)

      val listed = call(
        tools,
        "list_federated_sources",
        McpPrincipal.StaticKey,
        "tenant"   -> Json.fromString(Tenant0),
        "database" -> Json.fromString(TenantDb)
      )
      listed.toOption.get.hcursor.downField("sources").values.get should have size 1

      call(
        tools,
        "delete_federated_secret",
        McpPrincipal.StaticKey,
        "tenant"   -> Json.fromString(Tenant0),
        "database" -> Json.fromString(TenantDb),
        "alias"    -> Json.fromString("fedpg"),
        "name"     -> Json.fromString("PG_PASSWORD")
      ).isRight shouldBe true

      call(
        tools,
        "delete_federated_source",
        McpPrincipal.StaticKey,
        "tenant"   -> Json.fromString(Tenant0),
        "database" -> Json.fromString(TenantDb),
        "alias"    -> Json.fromString("fedpg")
      ).isRight shouldBe true
    }

  "federation tools" should "return federation_disabled when the handler is absent" in
    withTools(withFederation = false) { (tools, _) =>
      val fedToolNames = List(
        "list_federated_sources",
        "upsert_federated_source",
        "delete_federated_source",
        "set_federated_secret",
        "delete_federated_secret"
      )
      fedToolNames.foreach { name =>
        val out = call(
          tools,
          name,
          McpPrincipal.StaticKey,
          "tenant"    -> Json.fromString(Tenant0),
          "database"  -> Json.fromString(TenantDb),
          "alias"     -> Json.fromString("x"),
          "name"      -> Json.fromString("x"),
          "setup_sql" -> Json.fromString("x")
        )
        withClue(s"$name: $out")(out.isLeft shouldBe true)
        out.swap.toOption.get should include("federation_disabled")
      }
    }

  // --- Findings 1 & 2: PAT-aware scope gates on the federated-source handlers over MCP -------
  // REST resolves scope only from session tokens' scopeOf; MCP admits both session tokens and
  // PATs. Before the fix, FederatedSourceHandlers was constructed with a session-only scopeOf,
  // so a tenant-scoped admin PAT (unresolvable there) fell through the "unresolvable scope"
  // admit-arm meant for static-key/open-mode callers -- bypassing both the externalRef
  // superuser gate and (since MCP never traverses the REST URL-perimeter guard) tenant scoping.

  "set_federated_secret" should
    "reject an externalRef secret from a tenant-scoped admin PAT with superuser_required" in
    withTools(withFederation = true) { (tools, _) =>
      val created = call(
        tools,
        "upsert_federated_source",
        McpPrincipal.StaticKey,
        "tenant"    -> Json.fromString(Tenant0),
        "database"  -> Json.fromString(TenantDb),
        "alias"     -> Json.fromString("fedpg"),
        "setup_sql" -> Json.fromString("INSTALL postgres;")
      )
      withClue(created)(created.isRight shouldBe true)

      val out = call(
        tools,
        "set_federated_secret",
        tenantPat,
        "tenant"       -> Json.fromString(Tenant0),
        "database"     -> Json.fromString(TenantDb),
        "alias"        -> Json.fromString("fedpg"),
        "name"         -> Json.fromString("X"),
        "external_ref" -> Json.fromString("env:QOD_SESSION_JWT_SECRET")
      )
      withClue(out)(out.isLeft shouldBe true)
      out.swap.toOption.get should include("superuser_required")
    }

  "upsert_federated_source" should
    "reject a tenant-A-scoped admin PAT addressing tenant B with tenant_forbidden" in
    withTools(withFederation = true) { (tools, _) =>
      val out = call(
        tools,
        "upsert_federated_source",
        tenantPat,
        "tenant"    -> Json.fromString("tenant-b"),
        "database"  -> Json.fromString(TenantDb),
        "alias"     -> Json.fromString("fedpg"),
        "setup_sql" -> Json.fromString("INSTALL postgres;")
      )
      withClue(out)(out.isLeft shouldBe true)
      out.swap.toOption.get should include("tenant_forbidden")
    }

  "manifest_export" should "return YAML as a string result" in
    withTools() { (tools, _) =>
      val out = call(tools, "manifest_export", McpPrincipal.StaticKey)
      withClue(out)(out.isRight shouldBe true)
      val yaml =
        out.toOption.get.asString.getOrElse(fail("manifest_export did not return a string"))
      yaml should include("apiVersion: quack-on-demand/v1")
      yaml should include("kind: ConfigManifest")
    }

  "manifest_import" should "apply a YAML manifest and report per-kind counts" in
    withTools() { (tools, _) =>
      val yaml =
        """apiVersion: quack-on-demand/v1
          |kind: ConfigManifest
          |exportedAt: '2026-06-05T12:00:00Z'
          |exportedFrom: { managerVersion: test, hostname: host }
          |tenants:
          |  - name: imported_demo
          |""".stripMargin
      val out =
        call(tools, "manifest_import", McpPrincipal.StaticKey, "yaml" -> Json.fromString(yaml))
      withClue(out)(out.isRight shouldBe true)
    }

  "create_pat" should "mint a child PAT for the calling PAT and list it" in
    withTools() { (tools, raw) =>
      val principal = adminPat(raw)

      val created = call(
        tools,
        "create_pat",
        principal,
        "name" -> Json.fromString("agent-child")
      )
      withClue(created)(created.isRight shouldBe true)
      val childId = created.toOption.get.hcursor.get[String]("id").toOption.get

      val listed = call(tools, "list_pats", principal)
      listed.toOption.get.hcursor
        .downField("tokens")
        .values
        .get
        .exists(_.hcursor.get[String]("id").toOption.contains(childId)) shouldBe true

      call(tools, "revoke_pat", principal, "id" -> Json.fromString(childId)).isRight shouldBe true
      call(tools, "delete_pat", principal, "id" -> Json.fromString(childId)).isRight shouldBe true
    }

  it should "fail for the static key (no PAT identity)" in
    withTools() { (tools, _) =>
      val out = call(
        tools,
        "create_pat",
        McpPrincipal.StaticKey,
        "name" -> Json.fromString("agent-child")
      )
      out.isLeft shouldBe true
    }

  "get_config" should "list server config entries for the static key" in
    withTools() { (tools, _) =>
      val out = call(tools, "get_config", McpPrincipal.StaticKey)
      withClue(out)(out.isRight shouldBe true)
      val entries = out.toOption.get.hcursor.downField("entries").values.get
      entries.nonEmpty shouldBe true
      entries.foreach { e =>
        val cur = e.hcursor
        if cur.get[Boolean]("sensitive").toOption.contains(true) then
          cur.get[String]("value").toOption.get should (be("(set)") or be("(unset)"))
      }
    }

  "statement_history / usage_trends / usage_report" should "return empty results on a noop store" in
    withTools() { (tools, _) =>
      val history = call(tools, "statement_history", McpPrincipal.StaticKey)
      withClue(history)(history.isRight shouldBe true)
      history.toOption.get.hcursor.downField("statements").values.get shouldBe empty

      val trends = call(
        tools,
        "usage_trends",
        McpPrincipal.StaticKey,
        "granularity" -> Json.fromString("day")
      )
      withClue(trends)(trends.isRight shouldBe true)
      trends.toOption.get.hcursor.downField("buckets").values.get shouldBe empty

      val usage = call(tools, "usage_report", McpPrincipal.StaticKey)
      withClue(usage)(usage.isRight shouldBe true)
      usage.toOption.get.hcursor.downField("groups").values.get shouldBe empty
    }
