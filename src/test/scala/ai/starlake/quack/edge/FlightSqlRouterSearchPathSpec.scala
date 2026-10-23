package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.sql.PostgresAclValidator
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, RolePermission}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.Instant
import scala.collection.concurrent.TrieMap

/** The router pins the pool's default schema with a `USE <db>.<schema>` prelude and the validator
  * qualifies bare names against that same schema. These cases drive the whole routed pipeline
  * (validator included) and look at what reaches the node, so both halves of the contract are
  * pinned together: a batch that would move the schema after the prelude never reaches the node for
  * an ACL-checked caller, and every multi-statement batch that does reach it starts from the schema
  * the ACL assumed.
  */
class FlightSqlRouterSearchPathSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_db", "sales")
  private val Prelude = "USE acme_db.tpch1; "

  private def setup() =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          26000 + n.size,
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
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    val dir = Files.createTempDirectory("qod-search-path")
    sup
      .createTenantDb(
        poolKey.tenant,
        poolKey.tenantDb,
        TenantDbKind.DuckDbFile,
        Map("dbName" -> "acme_db", "schemaName" -> "tpch1"),
        s"$dir/acme.duckdb"
      )
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
        sent += sql
        IO.pure(TestArrow.okResponse())
    val validator = new PostgresAclValidator(
      defaultDatabase = "acme_db",
      defaultSchema = "tpch1",
      tenantCatalogs = t => if t == "t-1" then Set("acme_db") else Set.empty
    )
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator
    )
    (router, sent)

  // alice holds ALL on acme_db.tpch1.t and nothing else; acme_db.other.t is a different table.
  private val alice = EffectiveSet(
    RbacUser("u-1", Some("t-1"), "alice", kind = "user"),
    Nil,
    Nil,
    List(RolePermission("rp-1", "r-1", "acme_db", "tpch1", "t", "ALL")),
    Nil
  )
  private val root = EffectiveSet(RbacUser("u-su", None, "admin", "admin"), Nil, Nil, Nil, Nil)
  // A tenant-wide `*.*.* ALL` holder: still ACL-checked, but the wildcard also covers statements
  // the parser cannot read, such as a batch opening with BEGIN.
  private val tenantAdmin = EffectiveSet(
    RbacUser("u-2", Some("t-1"), "bob", kind = "admin"),
    Nil,
    Nil,
    List(RolePermission("rp-2", "r-2", "*", "*", "*", "ALL")),
    Nil
  )

  private def run(sql: String, eff: EffectiveSet = alice, conn: String = "c-1") =
    val (router, sent) = setup()
    val out            = router
      .execute(conn, eff.user.username, poolKey, sql, Some(eff))
      .unsafeRunSync()
    out.foreach(_.close())
    (out, sent.toList)

  "a batch that moves the schema" should "be refused before it reaches the node" in:
    List(
      "SET search_path='acme_db.other'; SELECT * FROM t",
      "SET schema='other'; SELECT * FROM t",
      "SET SESSION search_path='acme_db.other'; SELECT * FROM t",
      "PRAGMA search_path='acme_db.other'; SELECT * FROM t",
      "USE acme_db.other; SELECT * FROM t",
      "USE other; SELECT * FROM t",
      "RESET search_path; SELECT * FROM t",
      "SET search_path='acme_db.other'; DELETE FROM t",
      "SELECT $$'$$; SET search_path='acme_db.other'; SELECT * FROM t"
    ).foreach { sql =>
      withClue(sql) {
        val (out, sent) = run(sql)
        out match
          case Left(RouterFailure.AccessDenied(m)) => m should include("own statement")
          case other                               => fail(s"expected AccessDenied, got $other")
        sent shouldBe empty
      }
    }

  it should "still run each statement sent on its own" in:
    val (o1, s1) = run("SET search_path='acme_db.other'")
    o1 shouldBe a[Right[?, ?]]
    s1 shouldBe List("SET search_path='acme_db.other'")
    val (o2, s2) = run("SELECT * FROM t")
    o2 shouldBe a[Right[?, ?]]
    s2 shouldBe List(Prelude + "SELECT * FROM t")

  it should "run for a superuser, who is not subject to the ACL" in:
    val (out, sent) = run("SET search_path='acme_db.other'; SELECT * FROM t", eff = root)
    out shouldBe a[Right[?, ?]]
    sent shouldBe List(Prelude + "SET search_path='acme_db.other'; SELECT * FROM t")

  "a multi-statement batch" should "start from the prelude even when its first statement skips it" in:
    // On a node connection the session keeps (a client transaction on the native front door, the
    // embedded client's cached session) an earlier lone `USE acme_db.other` is still in effect, so
    // `t` here would resolve to acme_db.other.t while the ACL checked acme_db.tpch1.t.
    List(
      "BEGIN; SELECT * FROM t",
      // A session setting: node-wide ones (threads, ...) are reserved to superusers.
      "SET TimeZone='UTC'; SELECT * FROM t",
      "SET VARIABLE x = 1; SELECT * FROM t"
    ).foreach { sql =>
      withClue(sql) {
        val (out, sent) = run(sql, eff = tenantAdmin)
        out shouldBe a[Right[?, ?]]
        sent shouldBe List(Prelude + sql)
      }
    }

  it should "place the prelude after leading statements that end a transaction" in:
    // Inside an aborted transaction DuckDB refuses everything but ROLLBACK, the USE prelude
    // included, so `USE ...; ROLLBACK; ...` would fail and leave the session aborted. The prelude
    // goes after the leading run of ROLLBACK / ABORT / COMMIT / END instead: those statements cannot
    // move name resolution (a search path is not transactional on DuckDB), and every later
    // statement still runs under the default schema the ACL checked.
    List(
      "ROLLBACK; SELECT * FROM t"         -> "ROLLBACK; USE acme_db.tpch1; SELECT * FROM t",
      "abort ; SELECT * FROM t"           -> "abort ; USE acme_db.tpch1; SELECT * FROM t",
      "COMMIT; SELECT * FROM t"           -> "COMMIT; USE acme_db.tpch1; SELECT * FROM t",
      "ROLLBACK; COMMIT; SELECT * FROM t" -> "ROLLBACK; COMMIT; USE acme_db.tpch1; SELECT * FROM t",
      "/* c */ END; SELECT * FROM t"      -> "/* c */ END; USE acme_db.tpch1; SELECT * FROM t",
      "SELECT 1; ROLLBACK; SELECT * FROM t" -> (Prelude + "SELECT 1; ROLLBACK; SELECT * FROM t")
    ).foreach { case (sql, expected) =>
      withClue(sql) {
        val (out, sent) = run(sql, eff = tenantAdmin)
        out shouldBe a[Right[?, ?]]
        sent shouldBe List(expected)
      }
    }
    // The text the validator checked goes out byte for byte, with only the USE inserted: comment
    // fragments, spacing and the trailing semicolon are kept.
    val kept = "ROLLBACK; /* keep */ ;  SELECT * FROM t; -- tail"
    run(kept, eff = tenantAdmin)._2 shouldBe
      List("ROLLBACK; /* keep */ ; USE acme_db.tpch1;  SELECT * FROM t; -- tail")
    // Nothing after the transaction end: nothing to resolve, sent as is.
    run("ROLLBACK; COMMIT", eff = root)._2 shouldBe List("ROLLBACK; COMMIT")

  it should "leave a lone transaction-control or SET statement unprefixed" in:
    run("BEGIN", eff = root)._2 shouldBe List("BEGIN")
    run("SET threads=2", eff = root)._2 shouldBe List("SET threads=2")
    run("USE other", eff = root)._2 shouldBe List("USE acme_db.other")

  "a SET VARIABLE that reads a table" should "be refused for a user without a grant on it" in:
    val (out, sent) =
      run(
        "SET VARIABLE x = (SELECT string_agg(v, ',') FROM acme_db.other.t); SELECT getvariable('x')"
      )
    out shouldBe a[Left[?, ?]]
    out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
    sent shouldBe empty
