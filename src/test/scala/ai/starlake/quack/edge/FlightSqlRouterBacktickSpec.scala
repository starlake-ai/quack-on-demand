package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import ai.starlake.quack.edge.sql.{BacktickScreen, PostgresAclValidator, StatementValidator}
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
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  RbacUser,
  RolePermission,
  RoleRowPolicy
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.Instant
import scala.collection.concurrent.TrieMap

/** On DuckDB 1.5.6 a backtick is a binary operator, so once a macro named with a backtick exists
  * the text between two backticks runs as SQL: `SELECT 1 ` + backtick + ` 2; SET search_path=...;
  * SELECT 3 ` + backtick + ` 4` runs the SET, and a call between the pair runs (both checked on the
  * 1.5.6 CLI). The shared lexer reads that pair as one quoted identifier, so the router refuses the
  * backtick for every caller the screens protect while the engine is 1.x (see BacktickScreen).
  */
class FlightSqlRouterBacktickSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_db", "sales")

  private def setup(aclOn: Boolean, lockdown: Boolean, policies: Boolean) =
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
    val dir = Files.createTempDirectory("qod-backtick")
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
    val validator =
      if aclOn then
        new PostgresAclValidator(
          defaultDatabase = "acme_db",
          defaultSchema = "tpch1",
          tenantCatalogs = t => if t == "t-1" then Set("acme_db") else Set.empty
        )
      else StatementValidator.allowAll
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      lockdownFor = _ => lockdown,
      protectedWriteGuard =
        new ProtectedWriteGuard(new ColumnCatalog.MapCatalog(Map.empty), rlsEnabled = policies)
    )
    (router, sent)

  private val alice = EffectiveSet(
    RbacUser("u-1", Some("t-1"), "alice", kind = "user"),
    Nil,
    Nil,
    List(RolePermission("rp-1", "r-1", "acme_db", "tpch1", "t", "ALL")),
    Nil
  )
  // `*.*.* ALL` covers an unparseable statement, so only the backtick screen refuses it.
  private val tenantAdmin = EffectiveSet(
    RbacUser("u-2", Some("t-1"), "bob", kind = "admin"),
    Nil,
    Nil,
    List(RolePermission("rp-2", "r-2", "*", "*", "*", "ALL")),
    Nil
  )
  private val root = EffectiveSet(RbacUser("u-su", None, "admin", "admin"), Nil, Nil, Nil, Nil)

  private def run(
      sql: String,
      eff: EffectiveSet,
      aclOn: Boolean = true,
      lockdown: Boolean = false,
      policies: Boolean = false
  ) =
    val (router, sent) = setup(aclOn, lockdown, policies)
    val out            = router
      .execute("c-1", eff.user.username, poolKey, sql, Some(eff))
      .unsafeRunSync()
    out.foreach(_.close())
    (out, sent.toList)

  private val B = "`"
  // A backtick pair hiding two separators and the SET between them.
  private val hidesSeparator =
    s"SELECT 1 $B 2; SET search_path='acme_db.other'; SELECT * FROM t WHERE 3 $B 4 = 7"
  // A backtick pair hiding a call the screens deny.
  private val hidesQueryTable =
    s"SELECT 1 $B 2 AS a, * FROM query_table('acme_db.other.t') WHERE 3 $B 4 = 7"
  private val hidesReadCsv =
    s"SELECT 1 $B 2 AS a, * FROM read_csv('/tmp/x.csv') WHERE 3 $B 4 = 7"

  private def refused(result: (Either[RouterFailure, ?], List[String])) =
    val (out, sent) = result
    out match
      case Left(RouterFailure.AccessDenied(m)) => m should include("backtick")
      case other                               => fail(s"expected AccessDenied, got $other")
    sent shouldBe empty

  "a backtick outside literals" should "be refused for an ACL-checked caller" in:
    assume(BacktickScreen.active, "backtick screen is inert on this DuckDB pin")
    for
      eff <- List(alice, tenantAdmin)
      sql <- List(hidesSeparator, hidesQueryTable)
    do withClue(s"${eff.user.username}: $sql")(refused(run(sql, eff)))

  it should "be refused on a lockdown pool with the ACL off" in:
    assume(BacktickScreen.active, "backtick screen is inert on this DuckDB pin")
    List(hidesSeparator, hidesReadCsv).foreach { sql =>
      withClue(sql)(refused(run(sql, alice, aclOn = false, lockdown = true)))
    }

  it should "run for a superuser" in:
    List(hidesSeparator, hidesReadCsv).foreach { sql =>
      withClue(sql) {
        val (out, sent) = run(sql, root, lockdown = true)
        out shouldBe a[Right[?, ?]]
        sent should have size 1
        sent.head should endWith(sql)
      }
    }

  it should "run with the ACL off on a pool without lockdown" in:
    val (out, sent) = run(hidesSeparator, alice, aclOn = false)
    out shouldBe a[Right[?, ?]]
    sent should have size 1
    sent.head should endWith(hidesSeparator)

  // A row policy on acme_db.tpch1.t: the protected-write guard runs even with the ACL and
  // lockdown off, and its CatalogReachingCalls check must not be blinded by a backtick pair.
  private val rowPolicy = RoleRowPolicy("rp-r", "r-1", "*", "tpch1", "t", "v = 1")

  it should "be refused for a principal with a row policy, ACL and lockdown off" in:
    assume(BacktickScreen.active, "backtick screen is inert on this DuckDB pin")
    val eff = alice.copy(rowPolicies = List(rowPolicy))
    refused(run(hidesQueryTable, eff, aclOn = false, policies = true))

  it should "run for a superuser with a row policy, ACL and lockdown off" in:
    val su          = root.copy(rowPolicies = List(rowPolicy))
    val (out, sent) = run(hidesQueryTable, su, aclOn = false, policies = true)
    out shouldBe a[Right[?, ?]]
    sent should have size 1
    sent.head should endWith(hidesQueryTable)

  "a backtick inside a string literal or a comment" should "be admitted" in:
    val sql = s"SELECT '$B;$B' AS a, " + "\"x" + B + "y\"" + s" FROM t /* $B */ -- $B"
    for (aclOn, lockdown) <- List((true, false), (false, true)) do
      withClue(s"acl=$aclOn lockdown=$lockdown") {
        val (out, sent) = run(sql, alice, aclOn = aclOn, lockdown = lockdown)
        out shouldBe a[Right[?, ?]]
        sent should have size 1
        sent.head should endWith(sql)
      }
