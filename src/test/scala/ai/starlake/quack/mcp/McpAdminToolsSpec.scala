// src/test/scala/ai/starlake/quack/mcp/McpAdminToolsSpec.scala
package ai.starlake.quack.mcp

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.edge.{ActiveStatementRegistry, StatementHistoryStore}
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  ActiveStatementHandlers,
  AuditHandlers,
  MaintenanceHandlers,
  NodeHandlers,
  PoolHandlers,
  SessionTokenStore,
  SetPoolAutoscaleRequest,
  TagHandlers,
  TenantDbHandlers
}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.ondemand.telemetry.{AuditRecorder, NoopTelemetryStore}
import cats.effect.unsafe.implicits.global
import java.time.Instant
import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Admin-tier MCP tool contract over the in-memory supervisor: scale (incl. the autoscale-band
  * refusal), suspend/resume, statement kill scoping, the protect-only tag rule, and audit search.
  */
class McpAdminToolsSpec extends AnyFlatSpec with Matchers:

  private val Tenant0  = "acme"
  private val TenantDb = "acme_default"
  private val Pool     = "sales"
  private val Key      = PoolKey(Tenant0, TenantDb, Pool)

  private val patToken = "qod_pat_alice"

  private def adminPat(tenant: String = Tenant0): McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u1", tenant = Some(tenant), username = "alice", role = "admin"),
        patId = "pat-1",
        scope = SessionScope(superuser = false, manageableTenants = Set(tenant)),
        isAdmin = true,
        restriction = TokenRestriction.Unrestricted
      ),
      patToken
    )

  private final class Fixture(auditRecorder: AuditRecorder = AuditRecorder.noop):
    val store    = new InMemoryControlPlaneStore()
    val backend  = ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop()
    val tracker  = new ai.starlake.quack.edge.adapter.NodeLoadTracker
    val sup      = new PoolSupervisor(backend, tracker, store)
    val registry = new ActiveStatementRegistry()

    // DuckLake kind via direct store upsert (the supervisor's create path would probe a real
    // metastore): the tag tools gate on kind=ducklake, and everything else is kind-agnostic.
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
        dataPath = "/tmp/qod-mcp-admin-test"
      )
    )
    sup.restore()
    sup.createPool(Key, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val scopeOf: String => Option[SessionScope] =
      t =>
        if t == patToken then
          Some(SessionScope(superuser = false, manageableTenants = Set(Tenant0)))
        else None

    val pools      = new PoolHandlers(sup, tracker, audit = auditRecorder)
    val nodes      = new NodeHandlers(sup, tracker, store, StateChangePublisher.noop)
    val statements =
      new ActiveStatementHandlers(registry, new StatementHistoryStore(), store, haEnabled = false)
    val maintenance = new MaintenanceHandlers(sup, store)
    val tags        = new TagHandlers(
      sup,
      store,
      snapshotExists = (_, _, _) => true,
      snapshotsExist = (_, _, ids) => ids
    )
    val audit     = new AuditHandlers(NoopTelemetryStore)
    val tenantDbs = new TenantDbHandlers(sup)

    val tools =
      new McpAdminTools(pools, nodes, statements, maintenance, tags, audit, tenantDbs, scopeOf)

    def call(name: String, principal: McpPrincipal, args: (String, Json)*): Either[String, Json] =
      val tool = tools.tools.find(_.name == name).getOrElse(fail(s"tool $name not defined"))
      tool.adminOnly shouldBe true
      tool.run(principal, JsonObject(args*)).unsafeRunSync()

  "scale_pool" should "scale the pool and report the new size" in {
    val f   = new Fixture
    val out = f.call(
      "scale_pool",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool),
      "dual"     -> Json.fromInt(2)
    )
    out.isRight shouldBe true
    f.sup.get(Key).get.nodes should have size 2
  }

  it should "surface the outside_band refusal for a pool with a declared band" in {
    val f = new Fixture
    f.pools
      .setPoolAutoscale(
        SetPoolAutoscaleRequest(Tenant0, TenantDb, Pool, minNodes = Some(1), maxNodes = Some(2)),
        None
      )(f.scopeOf)
      .unsafeRunSync()
      .isRight shouldBe true
    val out = f.call(
      "scale_pool",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool),
      "dual"     -> Json.fromInt(5)
    )
    out.swap.toOption.get should include("outside_band")
  }

  // ------------------------------------------------------------------
  // caller attribution (Task 8, review round 2): the admin REST handlers an
  // MCP admin tool delegates to must attribute the ACTUAL audit row to the
  // resolved principal, not just carry a pat_id -- a PAT bearer curried in as
  // `apiKey` has to resolve through AuditRecorder's composed session lookup to
  // its owner's real username, exactly as Main.scala wires it at boot
  // (session lookup first, PatAuthenticator.sessionOf as fallback), with
  // patIdOf filling pat_id with zero changes to PoolHandlers.scalePool itself.
  // ------------------------------------------------------------------

  private def sessionFor(username: String, tenant: String) =
    SessionTokenStore.Session(
      AuthenticatedProfile(username, "admin", Set.empty, Map.empty, "db", Some(tenant)),
      SessionScope(superuser = false, manageableTenants = Set(tenant)),
      Instant.now()
    )

  /** Mirrors Main.scala's `auditRecorder` wiring: session lookup first, then a PAT-token lookup
    * (stand-in for `PatAuthenticator.sessionOf`/`resolve`), against a capturing store.
    */
  private def attributingAuditRecorder(): (AuditRecorder, RecordingTelemetryStore) =
    val telemetry = new RecordingTelemetryStore()
    val recorder  = new AuditRecorder(
      telemetry,
      sessionLookup = t => if t == patToken then Some(sessionFor("alice", Tenant0)) else None,
      patIdOf = t => if t == patToken then Some("pat-1") else None
    )
    (recorder, telemetry)

  "scale_pool via a PAT" should "attribute the audit row to the token's owner and its pat_id" in {
    val (recorder, telemetry) = attributingAuditRecorder()
    val f                     = new Fixture(recorder)
    f.call(
      "scale_pool",
      adminPat(),
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool),
      "dual"     -> Json.fromInt(2)
    ).isRight shouldBe true
    val e =
      telemetry.events.find(_.action == "pool.scale").getOrElse(fail("no pool.scale audit row"))
    (e.actor, e.patId) shouldBe ("alice", Some("pat-1"))
  }

  it should "leave the static key's audit row with a NULL pat_id (not the PAT owner's)" in {
    val (recorder, telemetry) = attributingAuditRecorder()
    val f                     = new Fixture(recorder)
    f.call(
      "scale_pool",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool),
      "dual"     -> Json.fromInt(2)
    ).isRight shouldBe true
    val e =
      telemetry.events.find(_.action == "pool.scale").getOrElse(fail("no pool.scale audit row"))
    // McpPrincipal.StaticKey carries rawToken = None by design (its docstring: "the handlers'
    // own static-key arm is not re-entered from here"), so no bearer reaches AuditRecorder at
    // all here and actorOf(None) resolves the pre-existing "anonymous" branch rather than
    // "static-key" (that label is for a REAL non-empty, non-PAT, non-session bearer -- see
    // AuditRecorderSpec's "leave pat_id at None for a static-key bearer" case for that path).
    // The one fact this test exists to pin is the one the review flagged: patId must stay None,
    // never leak the PAT owner's id onto an unrelated caller.
    e.patId shouldBe None
    e.actor should not be "alice"
  }

  "suspend_pool / resume_pool" should "round-trip the suspended flag" in {
    val f = new Fixture
    f.call(
      "suspend_pool",
      adminPat(),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool)
    ).isRight shouldBe true
    f.sup.get(Key).get.suspended shouldBe true
    f.call(
      "resume_pool",
      adminPat(),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool)
    ).isRight shouldBe true
    f.sup.get(Key).get.suspended shouldBe false
  }

  "kill_statement" should "404 a cross-tenant statement and report already-completed for unknown ids" in {
    val f  = new Fixture
    val id = f.registry.register("bob", "globex", "bi", "n1", "SELECT 1")
    // Tenant-scoped admin PAT: the other tenant's statement is invisible (404, no leak).
    val crossed = f.call("kill_statement", adminPat(), "id" -> Json.fromString(id))
    crossed.swap.toOption.get should include("not_found")
    // Unknown id: not an error (it may simply have finished) -- the handler's contract.
    val unknown = f.call("kill_statement", adminPat(), "id" -> Json.fromString("nope"))
    unknown.toOption.get.hcursor.get[String]("status").toOption shouldBe Some("already-completed")
  }

  "protect_tag" should "protect a tag when is_protected=true" in {
    val f = new Fixture
    f.call(
      "create_tag",
      McpPrincipal.StaticKey,
      "tenant"      -> Json.fromString(Tenant0),
      "database"    -> Json.fromString(TenantDb),
      "name"        -> Json.fromString("v1"),
      "snapshot_id" -> Json.fromLong(3L)
    ).isRight shouldBe true

    f.call(
      "protect_tag",
      McpPrincipal.StaticKey,
      "tenant"       -> Json.fromString(Tenant0),
      "database"     -> Json.fromString(TenantDb),
      "name"         -> Json.fromString("v1"),
      "is_protected" -> Json.True
    ).isRight shouldBe true
    f.store.listSnapshotTags(Tenant0, TenantDb).find(_.name == "v1").get.isProtected shouldBe true
  }

  "upsert_maintenance_policy" should "create a tenantdb-scope policy and list it back" in {
    val f  = new Fixture
    val up = f.call(
      "upsert_maintenance_policy",
      McpPrincipal.StaticKey,
      "tenant"         -> Json.fromString(Tenant0),
      "database"       -> Json.fromString(TenantDb),
      "scope_kind"     -> Json.fromString("tenantdb"),
      "retention_days" -> Json.fromInt(7)
    )
    up.isRight shouldBe true
    val listed = f.call(
      "get_maintenance_policy",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb)
    )
    listed.isRight shouldBe true
    val id = up.toOption.get.hcursor.get[String]("id").toOption.get
    f.call("delete_maintenance_policy", McpPrincipal.StaticKey, "id" -> Json.fromString(id))
      .isRight shouldBe true
  }

  "delete_tag" should "delete a created tag" in {
    val f = new Fixture
    f.call(
      "create_tag",
      McpPrincipal.StaticKey,
      "tenant"      -> Json.fromString(Tenant0),
      "database"    -> Json.fromString(TenantDb),
      "name"        -> Json.fromString("v1"),
      "snapshot_id" -> Json.fromLong(42L)
    ).isRight shouldBe true
    f.call(
      "delete_tag",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "name"     -> Json.fromString("v1")
    ).isRight shouldBe true
  }

  "protect_tag" should "toggle protection both directions" in {
    val f = new Fixture
    f.call(
      "create_tag",
      McpPrincipal.StaticKey,
      "tenant"      -> Json.fromString(Tenant0),
      "database"    -> Json.fromString(TenantDb),
      "name"        -> Json.fromString("v1"),
      "snapshot_id" -> Json.fromLong(42L)
    ).isRight shouldBe true
    val on = f.call(
      "protect_tag",
      McpPrincipal.StaticKey,
      "tenant"       -> Json.fromString(Tenant0),
      "database"     -> Json.fromString(TenantDb),
      "name"         -> Json.fromString("v1"),
      "is_protected" -> Json.True
    )
    // Wire field is "protected", not "isProtected": CatalogTagEntry's hand-rolled Codec (Dtos.scala)
    // renames it because "protected" is a Scala keyword -- see that codec's comment.
    on.toOption.get.hcursor.get[Boolean]("protected").toOption.get shouldBe true
    val off = f.call(
      "protect_tag",
      McpPrincipal.StaticKey,
      "tenant"       -> Json.fromString(Tenant0),
      "database"     -> Json.fromString(TenantDb),
      "name"         -> Json.fromString("v1"),
      "is_protected" -> Json.False
    )
    off.toOption.get.hcursor.get[Boolean]("protected").toOption.get shouldBe false
  }

  "audit_search" should "map filters through and admit the static key" in {
    val f   = new Fixture
    val out = f.call(
      "audit_search",
      McpPrincipal.StaticKey,
      "actor" -> Json.fromString("alice"),
      "q"     -> Json.fromString("pool"),
      "limit" -> Json.fromInt(10)
    )
    withClue(out)(out.isRight shouldBe true)
  }

  "list_pools and get_pool_status" should "answer for the static key" in {
    val f = new Fixture
    f.call("list_pools", McpPrincipal.StaticKey).isRight shouldBe true
    val status = f.call(
      "get_pool_status",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool)
    )
    withClue(status)(status.isRight shouldBe true)
  }

  "create_pool" should "create a pool with a role distribution" in {
    val f   = new Fixture
    val out = f.call(
      "create_pool",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString("etl"),
      "dual"     -> Json.fromInt(1)
    )
    out.isRight shouldBe true
    f.sup.get(PoolKey(Tenant0, TenantDb, "etl")).isDefined shouldBe true
  }

  "set_pool_disabled" should "flip the disabled flag" in {
    val f   = new Fixture
    val out = f.call(
      "set_pool_disabled",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool),
      "disabled" -> Json.True
    )
    out.isRight shouldBe true
    out.toOption.get.hcursor.get[Boolean]("disabled").toOption.get shouldBe true
  }

  "delete_pool" should "delete a pool with force" in {
    val f   = new Fixture
    val out = f.call(
      "delete_pool",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool),
      "force"    -> Json.True
    )
    out.isRight shouldBe true
    f.sup.get(Key).isEmpty shouldBe true
  }

  "set_node_max_concurrent" should "surface an error for an unknown node" in {
    val f   = new Fixture
    val out = f.call(
      "set_node_max_concurrent",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString(Pool),
      "node_id"  -> Json.fromString("no-such-node"),
      "max"      -> Json.fromInt(4)
    )
    out.isLeft shouldBe true
  }

  "create_database and list_databases_admin" should "round-trip a tenant-db" in {
    val f       = new Fixture
    val created = f.call(
      "create_database",
      McpPrincipal.StaticKey,
      "tenant" -> Json.fromString(Tenant0),
      "name"   -> Json.fromString("scratch"),
      "kind"   -> Json.fromString("memory")
    )
    withClue(created)(created.isRight shouldBe true)
    // create_database's "name" is a suffix the supervisor composes into "<tenant>_<suffix>"
    // (Names.normalizeTenantDbName); delete/update address the tenant-db by that full stored
    // name, so round-trip through the create response rather than re-typing the suffix.
    val fullName = created.toOption.get.hcursor.get[String]("name").toOption.get
    fullName shouldBe s"${Tenant0}_scratch"
    val listed = f.call(
      "list_databases_admin",
      McpPrincipal.StaticKey,
      "tenant" -> Json.fromString(Tenant0)
    )
    listed.toOption.get.hcursor
      .downField("tenantDbs")
      .values
      .get
      .size should be >= 2 // fixture's + scratch
    f.call(
      "delete_database",
      McpPrincipal.StaticKey,
      "tenant" -> Json.fromString(Tenant0),
      "name"   -> Json.fromString(fullName)
    ).isRight shouldBe true
  }

  "create_database" should "accept encrypted and encryption_key for kind=duckdb-file" in {
    val f       = new Fixture
    val created = f.call(
      "create_database",
      McpPrincipal.StaticKey,
      "tenant"    -> Json.fromString(Tenant0),
      "name"      -> Json.fromString("secure"),
      "kind"      -> Json.fromString("duckdb-file"),
      "data_path" -> Json.fromString("/tmp/qod-mcp-admin-test-secure.duckdb"),
      "metastore" -> Json.obj(
        "dbName"     -> Json.fromString("secure"),
        "schemaName" -> Json.fromString("main")
      ),
      "encrypted"      -> Json.True,
      "encryption_key" -> Json.fromString("caller-supplied-key")
    )
    withClue(created)(created.isRight shouldBe true)
    created.toOption.get.hcursor.get[Boolean]("encrypted").toOption shouldBe Some(true)
  }

  it should "refuse encryption_key on kind=ducklake, matching the REST validation" in {
    val f   = new Fixture
    val out = f.call(
      "create_database",
      McpPrincipal.StaticKey,
      "tenant"         -> Json.fromString(Tenant0),
      "name"           -> Json.fromString("secure2"),
      "kind"           -> Json.fromString("ducklake"),
      "encrypted"      -> Json.True,
      "encryption_key" -> Json.fromString("not-allowed-here")
    )
    out.isLeft shouldBe true
  }
