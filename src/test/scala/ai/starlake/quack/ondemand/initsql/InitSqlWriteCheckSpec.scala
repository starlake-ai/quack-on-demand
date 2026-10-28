package ai.starlake.quack.ondemand.initsql

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{RoleDistribution, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CreatePoolRequest,
  ErrorResponse,
  PoolHandlers,
  TenantDbHandlers,
  TenantDbRequest,
  UpdateTenantDbRequest
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** Task 20 Part D: a tenant admin's initSql is refused at write time when the tenant initSql
  * allowlist would skip a statement of it (qod-node --check-init, the node's own code), accepted
  * with a warning where this manager cannot check, and a superuser's is never checked.
  */
class InitSqlWriteCheckSpec extends AnyFlatSpec with Matchers:

  private val TenantAdmin: SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set("acme"))
  private val Superuser: SessionScope.Resolver = _ => SessionScope.Superuser

  /** The local qod-node's checker; the case cancels without a build. */
  private def nodeChecker: InitSqlChecker =
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    InitSqlChecker.nodeBinary(QodNodeTestBinary.path.get)

  /** Counts the checks, so a test can say none ran. */
  private final class Counting(inner: InitSqlChecker) extends InitSqlChecker:
    var calls                                  = 0
    def check(sql: String): IO[InitSqlVerdict] =
      calls += 1
      inner.check(sql)

  private final class Fixture(checker: InitSqlChecker):
    val tracker = new NodeLoadTracker
    val sup = new PoolSupervisor(new StubQuackBackend(), tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val dbs =
      new TenantDbHandlers(sup, requireEncryption = false, initSqlChecker = checker)
    val pools = new PoolHandlers(sup, tracker, initSqlChecker = checker)

    def createDb(sql: String, as: SessionScope.Resolver) =
      dbs
        .createTenantDb(
          TenantDbRequest(tenant = "acme", name = "prod", kind = "memory", initSql = sql),
          None
        )(as)
        .unsafeRunSync()

    def updateDb(sql: String, as: SessionScope.Resolver) =
      dbs
        .update(
          UpdateTenantDbRequest(tenant = "acme", name = "acme_prod", initSql = Some(sql)),
          None
        )(
          as
        )
        .unsafeRunSync()

    def createPool(sql: String, as: SessionScope.Resolver) =
      pools
        .createPool(
          CreatePoolRequest(
            tenant = "acme",
            tenantDb = "acme_prod",
            pool = "bi",
            size = 1,
            roleDistribution = RoleDistribution(0, 0, 1),
            initSql = Some(sql)
          ),
          None
        )(as)
        .unsafeRunSync()

  private def refusal(out: Either[(StatusCode, ErrorResponse), ?]): (Int, String, String) =
    out.swap.toOption
      .map(e => (e._1.code, e._2.error, e._2.message))
      .getOrElse(fail(s"admitted: $out"))

  /** The problem statement's forms (Part C corpus); the marker is a credential stand-in. */
  private val RefusedCorpus = List(
    "CREATE SECURE VIEW leak AS SELECT * FROM read_text('/etc/passwd')",
    "CREATE OR REPLACE SECRET s3k (TYPE s3, KEY_ID 'AKIAQODWRITEMARKER', SECRET 'x')",
    "SET temp_directory = '/tmp/other-tenant'",
    "COPY (SELECT 1) TO '/tmp/qod-init-out.csv'",
    "SELECT * FROM postgres_query('pg', 'SELECT 1')",
    "CREATE VIEW v AS SELECT * FROM range(setval('s', 1))",
    "SET memory_limit = current_setting('max_memory')",
    "INSTALL httpfs FROM 'http://example.com/repo'",
    "LOAD '/tmp/x.duckdb_extension'"
  )

  "a tenant admin's database initSql" should "be refused, naming position and reason, for each refused form" in {
    val f = new Fixture(nodeChecker)
    RefusedCorpus.foreach { sql =>
      withClue(sql.take(20)) {
        val (code, error, message) = refusal(f.createDb(s"SET threads = 2;\n$sql;", TenantAdmin))
        (code, error) shouldBe (400, "init_sql_refused")
        message should include("statement 2 of 2: ")
        message should not include "AKIAQODWRITEMARKER"
      }
    }
    f.sup.findTenantDb("acme", "acme_prod") shouldBe None
  }

  it should "be accepted, untrusted and without a warning, when every statement is allowed" in {
    val f   = new Fixture(nodeChecker)
    val out = f.createDb("SET memory_limit = '2GB';\nINSTALL httpfs;\nPRAGMA version;", TenantAdmin)
    val r   = out.fold(e => fail(e.toString), identity)
    (r.initSqlTrusted, r.initSqlWarning) shouldBe (false, None)
  }

  it should "be checked again when changed, and not when resent unchanged" in {
    val counting = new Counting(nodeChecker)
    val f        = new Fixture(counting)
    f.createDb("SET threads = 2;", TenantAdmin).isRight shouldBe true
    refusal(f.updateDb("SET threads = 2;\nSELECT 1;", TenantAdmin))._2 shouldBe "init_sql_refused"
    f.sup.findTenantDb("acme", "acme_prod").get.initSql shouldBe "SET threads = 2;"
    val before = counting.calls
    f.updateDb("SET threads = 2;", TenantAdmin).isRight shouldBe true
    counting.calls shouldBe before
  }

  "a superuser's initSql" should "never be checked, and be stored trusted" in {
    val counting = new Counting(nodeChecker)
    val f        = new Fixture(counting)
    val created  =
      f.createDb(RefusedCorpus.mkString(";\n"), Superuser).fold(e => fail(e.toString), identity)
    created.initSqlTrusted shouldBe true
    f.updateDb(RefusedCorpus.head, Superuser).isRight shouldBe true
    f.createPool(RefusedCorpus.head, Superuser)
      .fold(e => fail(e.toString), _.initSqlTrusted) shouldBe true
    counting.calls shouldBe 0
  }

  "a tenant admin's pool initSql" should "be refused like a database's" in {
    val f = new Fixture(nodeChecker)
    f.createDb("", Superuser).isRight shouldBe true
    val (code, error, message) = refusal(f.createPool("ATTACH '/tmp/x.duckdb' AS x", TenantAdmin))
    (code, error) shouldBe (400, "init_sql_refused")
    message should include("statement 1 of 1: ATTACH is not allowed")
    f.createPool("SET threads = 2;", TenantAdmin).isRight shouldBe true
  }

  "a caller outside the tenant" should "get its 403, unjudged" in {
    val counting                     = new Counting(nodeChecker)
    val f                            = new Fixture(counting)
    val other: SessionScope.Resolver =
      _ => SessionScope(superuser = false, manageableTenants = Set("globex"))
    refusal(f.createDb("SELECT 1;", other))._1 shouldBe 403
    counting.calls shouldBe 0
  }

  "a manager without a local qod-node" should "accept a tenant admin's initSql with a warning" in {
    val f   = new Fixture(InitSqlChecker.unavailable)
    val out = f.createDb("SELECT 1;", TenantAdmin).fold(e => fail(e.toString), identity)
    out.initSqlTrusted shouldBe false
    out.initSqlWarning.getOrElse(fail("no warning")) should include("was not checked here")
    val upd = f.updateDb("SET threads = 3;", TenantAdmin).fold(e => fail(e.toString), identity)
    upd.db.initSqlWarning should not be empty
    f.createPool("SELECT 2;", TenantAdmin)
      .fold(e => fail(e.toString), _.initSqlWarning) should not be
      empty
  }

  "qod-node --check-init" should "refuse the whole corpus and admit the allowlist's forms" in {
    val checker = nodeChecker
    checker.check(RefusedCorpus.mkString(";\n")).unsafeRunSync() match
      case InitSqlVerdict.Checked(n, refused) =>
        n shouldBe RefusedCorpus.size
        refused.map(_.statement) shouldBe (1 to RefusedCorpus.size).toList
      case other => fail(other.toString)
    checker
      .check("SET GLOBAL threads = 4;\nRESET memory_limit;\nLOAD json;\nPRAGMA database_list;")
      .unsafeRunSync() shouldBe InitSqlVerdict.Checked(4, Nil)
  }

  it should "fall back to unchecked when the binary does not answer" in {
    val missing = InitSqlChecker.nodeBinary(java.nio.file.Paths.get("/nonexistent/qod-node"))
    missing.check("SET threads = 2;").unsafeRunSync() shouldBe a[InitSqlVerdict.Unchecked]
  }

  "the boot report" should "warn only for a row that loses statements, or that cannot be checked" in {
    val row = InitSqlTrustReport.Row("acme", "database 'acme_prod'", "SET threads = 2;", "fix")
    InitSqlTrustReport.lineAfter(row, InitSqlVerdict.Checked(1, Nil)) shouldBe None
    InitSqlTrustReport
      .lineAfter(row, InitSqlVerdict.Checked(2, List(InitSqlRefusal(2, "SELECT is not allowed"))))
      .getOrElse(fail("no line")) should include("statement 2 of 2: SELECT is not allowed")
    InitSqlTrustReport
      .lineAfter(row, InitSqlVerdict.Unchecked("x"))
      .getOrElse(fail("no line")) should include("will be checked on the node")
  }
