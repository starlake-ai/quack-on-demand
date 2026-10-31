package ai.starlake.quack.ondemand.api

import ai.starlake.quack.{BranchingConfig, CatalogConfig}
import ai.starlake.quack.boot.{BranchWiring, RoutedExecutor}
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.authz.NodePrincipal
import ai.starlake.quack.mcp.{McpBranchTools, McpPrincipal}
import ai.starlake.quack.model.{Branch, NodeSpec, PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.branch.{
  BranchActor,
  BranchService,
  CloneResult,
  MergeExecutor,
  MergeFence
}
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, TableVersion}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BigIntVector, VarCharVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.collection.mutable
import scala.concurrent.duration.*

/** Who the branch change counts run as. Regression: the counter always ran as the system caller, so
  * anyone allowed to use a branch read per-table insert / delete / update counts of tables they
  * hold no grant on. Counts requested over REST or MCP now run as the requester (the routed
  * executor's handshake, then the node's verdict on that principal); a refused table keeps the zero
  * counts a failed count has always shown. The lifecycle (propose's summary) still counts as the
  * system caller.
  *
  * The service, the REST handler, the MCP tool, the counter (`BranchWiring.changeCounter`) and the
  * routed executor are the real ones over the in-memory store; the router leg stands in for the
  * node: it records the principal each count statement would run as and answers with the node's
  * verdict, here "alice may count region, not nation", any superuser and the system principal
  * everything.
  */
class BranchChangeCountsSpec extends AnyFlatSpec with Matchers:

  import IdentityFixtures.*

  private val AliceScope: SessionScope.Resolver = _ => SessionScope(false, Set("acme"))
  private val RootScope: SessionScope.Resolver  = _ => SessionScope.Superuser

  private class FakeReader(head: Long, changes: List[(Long, String)])
      extends DuckLakeCatalogReader(null):
    override def maxSnapshotId(): Option[Long]       = Some(head)
    override def tableVersions(): List[TableVersion] = List(
      TableVersion(2L, "main", "region", 1L, None),
      TableVersion(3L, "main", "nation", 1L, None)
    )
    override def snapshotChangesSince(fork: Long): List[(Long, String)] =
      changes.filter(_._1 > fork)
    override def columnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      List(CatalogColumnEntry(0, "x", "INTEGER", true, false))
    override def close(): Unit = ()

  /** `(change_type, n)` rows, the shape the counter folds. */
  private def summary(inserted: Long): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val schema    = new Schema(
      java.util.List.of(
        Field.nullable("change_type", new ArrowType.Utf8()),
        Field.nullable("n", new ArrowType.Int(64, true))
      )
    )
    val root   = VectorSchemaRoot.create(schema, allocator)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.allocateNew()
    root.getVector("change_type").asInstanceOf[VarCharVector].setSafe(0, "insert".getBytes("UTF-8"))
    root.getVector("n").asInstanceOf[BigIntVector].setSafe(0, inserted)
    root.setRowCount(1)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  private final class Fixture:
    val store = new InMemoryControlPlaneStore()
    val admin = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val sup =
      new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store, dbAdmin = admin)
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    val parent = sup
      .createTenantDb(
        "acme",
        "tpch",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "5432",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-branch-counts-spec/acme_tpch/"
      )
      .unsafeRunSync()
      .toOption
      .get
    sup.createPool(PoolKey("acme", parent.name, "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    // alice: a tenant user of acme who may connect to every pool (the branch's included).
    private val aliceRow =
      RbacUser(id = "u-alice", tenant = Some("acme"), username = "alice", kind = "admin")
    store.upsertUserIdentity(aliceRow)
    sup.grantPoolPermission("acme", None, Some(aliceRow.id), None).unsafeRunSync()

    private val mainReader   = new FakeReader(10L, Nil)
    private val branchReader =
      new FakeReader(12L, List((11L, "inlined_insert:2"), (12L, "inlined_insert:3")))

    /** What each count statement reached the node as: (table, principal, executor caller). */
    val counted = mutable.ListBuffer.empty[(String, NodePrincipal, ExecCaller)]

    private val route: RoutedExecutor.Run = (caller, _, sql, eff, _) =>
      IO {
        val table     = if sql.contains("'region'") then "region" else "nation"
        val principal = NodePrincipal.of(eff)
        counted += ((table, principal, caller))
        val allowed =
          principal == NodePrincipal.System || principal.superuser ||
            (principal.name == "alice" && table == "region")
        if allowed then
          Right(QueryResult(summary(if table == "region" then 7L else 5L), () => (), "n1", 1L))
        else Left(RouterFailure.AccessDenied(s"QoD: access denied: no grant on $table"))
      }
    private val executor =
      RoutedExecutor(sup, StatementClassifier.default, route)(recordExecution = false)

    val service = new BranchService(
      cfg = BranchingConfig(),
      sup = sup,
      store = store,
      resolveReader = (_, db) => if db == parent.name then mainReader else branchReader,
      cloneCatalog = (_, _, _, _) => Right(CloneResult(10L, 2, 10L)),
      mergeExecutor = new MergeExecutor:
        def run(spec: NodeSpec, batch: String): IO[Either[String, Unit]] = IO.pure(Right(()))
      ,
      mergeFence = new MergeFence:
        def arm(meta: Map[String, String], message: String, base: Long) = Right(())
        def disarm(meta: Map[String, String], message: String)          = ()
      ,
      counter = BranchWiring.changeCounter(
        executor,
        (b: Branch) => PoolKey("acme", b.tenantDbName, b.poolName),
        5.seconds
      ),
      purgeFiles = (_, _) => Right(())
    )
    val callers = restCaller(sessionOf, patOf)
    val preview = new CatalogPreviewHandlers(
      sup,
      store,
      callers,
      executor,
      (_, _) => new DuckLakeCatalogReader(null),
      CatalogConfig()
    )
    val handlers =
      new BranchHandlers(sup, service, preview, BranchHandlers.actorResolver(callers), callers)
    service
      .create("acme", parent.name, "feature", None, None, BranchActor("root", true), None)
      .unsafeRunSync()
      .fold(f => fail(s"branch create: $f"), identity)

    def overRest(token: String, scope: SessionScope.Resolver): Map[String, Long] =
      handlers
        .changes("acme", parent.name, "feature", Some(true), Some(token))(scope)
        .unsafeRunSync()
        .fold(e => fail(s"changes: $e"), identity)
        .tables
        .map(t => t.table -> t.inserted)
        .toMap

    def overMcp(): Map[String, Long] =
      val pat  = patOf(PatTok).get
      val tool = new McpBranchTools(handlers, AliceScope).tools.find(_.name == "branch_changes").get
      val out  = tool
        .run(
          new McpPrincipal.Pat(pat, PatTok),
          JsonObject(
            "database" -> Json.fromString(parent.name),
            "branch"   -> Json.fromString("feature")
          )
        )
        .unsafeRunSync()
        .fold(e => fail(s"branch_changes: $e"), identity)
      out.hcursor
        .downField("tables")
        .values
        .toList
        .flatten
        .map(t =>
          t.hcursor
            .get[String]("table")
            .toOption
            .get -> t.hcursor.get[Long]("inserted").toOption.get
        )
        .toMap

  "branch change counts over REST" should "run as the requester: no count of a table the node refuses them" in {
    val f = new Fixture
    f.overRest(SessionTok, AliceScope) shouldBe Map("region" -> 7L, "nation" -> 0L)
    f.counted.map(_._1).toSet shouldBe Set("region", "nation")
    f.counted.foreach { case (_, principal, caller) =>
      caller.system shouldBe false
      caller.identity shouldBe "alice"
      principal.name shouldBe "alice"
      principal should not be NodePrincipal.System
    }
  }

  "branch change counts over MCP" should "run as the PAT's owner, with the PAT, never as system" in {
    val f = new Fixture
    f.overMcp() shouldBe Map("region" -> 7L, "nation" -> 0L)
    f.counted should not be empty
    f.counted.foreach { case (_, principal, caller) =>
      caller.system shouldBe false
      caller.patId shouldBe Some(PatId)
      principal.name shouldBe "alice"
    }
  }

  "propose" should "still count every modified table as the system caller" in {
    val f          = new Fixture
    val (_, _, cs) = f.service
      .propose("acme", f.parent.name, "feature", BranchActor("root", true), None)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    cs.tables.map(t => t.table -> t.inserted).toMap shouldBe Map("region" -> 7L, "nation" -> 5L)
    f.counted.map(c => (c._1, c._3.system, c._2)).toSet shouldBe Set(
      ("region", true, NodePrincipal.System),
      ("nation", true, NodePrincipal.System)
    )
  }
