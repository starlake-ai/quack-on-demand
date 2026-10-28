package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.sql.RefusedForms
import ai.starlake.quack.model.{NodeSpec, PoolKey, RoleDistribution, RunningNode, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, RolePermission}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Task 19 part A in the router's pipeline: a refused form is rewritten before routing and before
  * every screen, so the statement the node gets is the rewrite as the metadata filter narrowed it
  * (the filter runs after the rewriter), a superuser's statement is not rewritten, and history
  * keeps the client's text. SUMMARIZE reads its columns back through the transport's own evaluator.
  */
class RefusedFormsRouterSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  private val tenantUser = EffectiveSet(
    RbacUser("u-1", Some("acme"), "alice", "user"),
    Nil,
    Nil,
    List(RolePermission("p-1", "r-1", "memory", "main", "t", "RO")),
    Nil
  )
  private val superuser = EffectiveSet(RbacUser("u-0", None, "root", "admin"), Nil, Nil, Nil, Nil)

  /** A router over one in-memory pool (catalog `memory`, schema `main`) with the metadata filter
    * mounted; every SQL the node is sent, in order, and the answer `answer` gives it.
    */
  private def setup(answer: String => QuackResponse = _ => TestArrow.okResponse()) =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          25000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(ai.starlake.quack.model.Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val sent   = scala.collection.mutable.ListBuffer.empty[String]
    val client = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO { sent.synchronized(sent += sql); answer(sql) }
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true)
    )
    (router, () => sent.synchronized(sent.toList))

  private def run(router: FlightSqlRouter, sql: String, eff: EffectiveSet) =
    router.execute("c-1", eff.user.username, poolKey, sql, Some(eff)).unsafeRunSync() match
      case Right(r) => r.close(); Right(r)
      case Left(f)  => Left(f)

  private val Path = RefusedForms.SessionPath("memory", "main")

  "a tenant's SHOW TABLES" should "reach the node as the rewrite the metadata filter narrowed" in {
    val (router, sent) = setup()
    run(router, "SHOW TABLES", tenantUser).isRight shouldBe true
    val sql = sent().last
    sql should startWith("USE memory.main; ")
    sql should not include "SHOW"
    // The rewrite's own predicate, then the filter's derived table around each catalog function:
    // the filter saw the rewritten statement, so it ran after the rewriter.
    sql should include("lower(schema_name) = lower('main')")
    sql should include("(SELECT * FROM duckdb_tables() WHERE database_name = 'memory'")
    sql should include("(SELECT * FROM duckdb_views() WHERE database_name = 'memory'")
    sql should include("table_name = 't'")
  }

  "a tenant's DESCRIBE" should "reach the node as the listing that reads the table" in {
    val (router, sent) = setup()
    run(router, "DESCRIBE t", tenantUser).isRight shouldBe true
    val sql = sent().last
    sql should include("AND NOT EXISTS (SELECT 1 FROM \"memory\".\"main\".\"t\" LIMIT 0)")
    sql should include("(SELECT * FROM duckdb_columns() WHERE database_name = 'memory'")
    sql should not include "DESCRIBE"
    // History keeps what the client sent.
    router.history.snapshot(5).headOption.map(r => (r.sql, r.status)) shouldBe
      Some(("DESCRIBE t", "ok"))
  }

  "a superuser's statement" should "reach the node as written" in {
    val (router, sent) = setup()
    run(router, "DESCRIBE t", superuser).isRight shouldBe true
    sent().last should endWith("DESCRIBE t")
    run(router, "SHOW TABLES", superuser).isRight shouldBe true
    sent().last should endWith("; SHOW TABLES")
  }

  "a statement the rewriter does not recognize" should "reach the node unchanged" in {
    val (router, sent) = setup()
    run(router, "DESCRIBE (SELECT 1)", tenantUser).isRight shouldBe true
    sent().last should endWith("DESCRIBE (SELECT 1)")
  }

  /** The node's answer to the SUMMARIZE's column listing: `id INTEGER`, `v VARCHAR`. */
  private val columns: String => QuackResponse = sql =>
    if sql.contains("duckdb_columns()") then
      QuackResponse.Ok(
        TestArrow.readerFor(
          "SELECT * FROM (VALUES ('id', 'INTEGER', 'NO', NULL, NULL, NULL), " +
            "('v', 'VARCHAR', 'YES', NULL, NULL, NULL)) AS c(column_name, column_type, \"null\", " +
            "\"key\", \"default\", extra)"
        ),
        1L,
        () => ()
      )
    else TestArrow.okResponse()

  "a tenant's SUMMARIZE" should "read its columns, then send DuckDB's summary query" in {
    val (router, sent) = setup(columns)
    run(router, "SUMMARIZE t", tenantUser).isRight shouldBe true
    val List(listing, summary) = sent(): @unchecked
    listing should include("NOT EXISTS (SELECT 1 FROM \"memory\".\"main\".\"t\" LIMIT 0)")
    summary should endWith(
      RefusedForms.summarizeSql(
        RefusedForms.Target("memory", "main", "t"),
        List(("id", "INTEGER"), ("v", "VARCHAR"))
      )
    )
    // One history row, the client's; the listing is the manager's own read.
    router.history.snapshot(5).map(r => (r.sql, r.status)) shouldBe List(("SUMMARIZE t", "ok"))
  }

  it should "answer the listing's refusal and send nothing more" in {
    val (router, sent) = setup(_ =>
      QuackResponse.Failed(
        QuackError.Permanent("Permission Error: QoD: user:u-1 lacks grants on memory.main.t:read")
      )
    )
    run(router, "SUMMARIZE t", tenantUser) match
      case Left(f) => f.reason should include("lacks grants on memory.main.t:read")
      case other   => fail(s"expected the listing's refusal, got $other")
    sent().size shouldBe 1
    router.history.snapshot(5).map(r => (r.sql, r.status)) shouldBe List(
      ("SUMMARIZE t", "permanent")
    )
  }

  it should "stay unchanged on a transport with no evaluator" in {
    val (router, sent) = setup()
    val out            = router
      .executeWith(
        "c-2",
        "alice",
        poolKey,
        "SUMMARIZE t",
        Some(tenantUser),
        (node, wrappedSql, _, _, principal) =>
          router.adapter.send(node, wrappedSql, principal, None).map(NodeOutcome.fromQuackResponse)
      )
      .unsafeRunSync()
    out.foreach(_.close())
    sent().last should endWith("SUMMARIZE t")
  }

  "the session path" should "be the router's USE prelude target" in {
    val (router, _) = setup()
    router.supervisor.get(poolKey).flatMap(FlightSqlRouter.sessionPath) shouldBe Some(Path)
    router.supervisor.get(poolKey).flatMap(FlightSqlRouter.usePath) shouldBe Some("memory.main")
  }
