package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, RoleDistribution, RunningNode, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Unit-tests the REST surface added for `qodstate_tenant_db` against
  * an in-memory supervisor (no Postgres needed). */
class TenantDbHandlersSpec extends AnyFlatSpec with Matchers:

  private final class StubBackend extends QuackBackend:
    private val nodes = TrieMap.empty[String, RunningNode]
    def start(spec: NodeSpec): IO[RunningNode] = IO {
      val n = RunningNode(spec.nodeId, spec.poolKey, spec.role,
        "127.0.0.1", 21000 + nodes.size, "tok", Some(1L), None, Instant.EPOCH)
      nodes.put(spec.nodeId, n); n
    }
    def stop(id: String):    IO[Unit] = IO { nodes.remove(id); () }
    def isAlive(id: String): Boolean  = nodes.contains(id)
    def discoverExisting():  IO[List[RunningNode]] = IO.pure(nodes.values.toList)
    def cleanup():           IO[Unit] = IO { nodes.clear() }

  private def freshHandlers(): TenantDbHandlers =
    val sup = new PoolSupervisor(new StubBackend, new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme", Map.empty)).unsafeRunSync()
    new TenantDbHandlers(sup)

  "TenantDbHandlers.createTenantDb" should
    "compose `${tenant}_${name}` and return the persisted row" in:
    val h   = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "prod",
      kind      = "ducklake",
      metastore = Map(
        "pgHost"     -> "h",
        "pgPort"     -> "0",
        "pgUser"     -> "u",
        "pgPassword" -> "secret",
        "dbName"     -> "ignored",
        "schemaName" -> "main"
      ),
      dataPath  = "/data/acme_prod"
    )).unsafeRunSync()
    out.isRight shouldBe true
    val td = out.toOption.get
    td.tenant   shouldBe "acme"
    td.name     shouldBe "acme_prod"
    td.kind     shouldBe "ducklake"
    td.dataPath shouldBe "/data/acme_prod"
    // pgPassword must be stripped on the response surface.
    td.metastore.contains("pgPassword") shouldBe false
    td.metastore("schemaName")          shouldBe "main"

  it should "reject when tenant + name are empty (400)" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant = "", name = "", kind = "memory", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 400

  it should "404 when the tenant doesn't exist" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant = "ghost", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 404

  it should "409 on a duplicate (tenant, name) pair" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    val again = h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    again.isLeft shouldBe true
    again.swap.toOption.get._1.code shouldBe 409

  "TenantDbHandlers.listTenantDbs" should "return the tenant's databases under the new wire field" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod",  kind = "memory", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "stage", kind = "memory", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    val out = h.listTenantDbs("acme").unsafeRunSync().toOption.get
    out.tenantDbs.map(_.name).sorted shouldBe List("acme_prod", "acme_stage")

  it should "return an empty list for an unknown tenant" in:
    val h = freshHandlers()
    val out = h.listTenantDbs("ghost").unsafeRunSync().toOption.get
    out.tenantDbs shouldBe Nil

  "TenantDbHandlers.deleteTenantDb" should "remove a database with no pools" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    h.deleteTenantDb(TenantDbOpRequest("acme", "acme_prod")).unsafeRunSync().isRight shouldBe true
    h.listTenantDbs("acme").unsafeRunSync().toOption.get.tenantDbs shouldBe Nil

  it should "404 when the database doesn't exist" in:
    val h = freshHandlers()
    val out = h.deleteTenantDb(TenantDbOpRequest("acme", "acme_ghost")).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 404

  "TenantDbHandlers.createTenantDb (kind=memory)" should
    "accept empty metastore and empty dataPath" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "mem",
      kind      = "memory",
      metastore = Map.empty,
      dataPath  = ""
    )).unsafeRunSync()
    out.isRight shouldBe true
    val td = out.toOption.get
    td.kind shouldBe "memory"

  it should "reject non-empty metastore violation (kind=memory + non-empty metastore)" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "bad",
      kind      = "memory",
      metastore = Map("dbName" -> "tpch"),
      dataPath  = ""
    )).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 400
    out.swap.toOption.get._2.message should include("empty metastore")

  "TenantDbHandlers.createTenantDb (kind=duckdb-file)" should
    "round-trip metastore + dataPath" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "file",
      kind      = "duckdb-file",
      metastore = Map("dbName" -> "mydata", "schemaName" -> "main"),
      dataPath  = "/tmp/foo.duckdb"
    )).unsafeRunSync()
    out.isRight shouldBe true
    val td = out.toOption.get
    td.kind shouldBe "duckdb-file"
    td.metastore("dbName") shouldBe "mydata"

  it should "reject unknown kind string with 400" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "bad", kind = "postgres", metastore = Map.empty, dataPath = ""
    )).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 400
    out.swap.toOption.get._2.message should include("unknown TenantDbKind")

  "TenantDbHandlers.createTenantDb (defaults overrides)" should
    "round-trip defaultDatabase and defaultSchema on the response" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant          = "acme",
      name            = "mem2",
      kind            = "memory",
      metastore       = Map.empty,
      dataPath        = "",
      defaultDatabase = Some("fedpg"),
      defaultSchema   = Some("public")
    )).unsafeRunSync()
    val td = out.toOption.get
    td.defaultDatabase shouldBe Some("fedpg")
    td.defaultSchema   shouldBe Some("public")
