package ai.starlake.quack.it.authz

import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.Locale
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The OPA dry run through a node (decision 25), on a real manager and a real qod-node: `POST
  * /api/tenant/opaTest` with SQL runs the statement on the pool's node as the named user, the
  * decision endpoint asks a WireMock OPA past its decision cache and records the call, and the
  * statement never runs, even when OPA allows a write. Manager on 20971 (REST) / 31371 (FlightSQL)
  * / 29471 (front door), relay 20981, nodes 23260-23264. Cancelled without the test Postgres or a
  * qod-node build.
  */
class OpaDryRunLiveSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val run           = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Tenant        = s"opadry_$run"
  private val Pw            = "ana-dry-pw-1"
  private val StatementPath = "/v1/data/qod/authz/statement"
  private val ConnectPath   = "/v1/data/qod/authz/connect"

  private val opa               = new WireMockServer(options().dynamicPort())
  private var stack: AuthzStack = null
  private var root: Path        = null
  private var db: String        = ""

  private def ready: Boolean =
    TestPostgres.reachable && QodNodeTestBinary.path.nonEmpty && !scala.util.Properties.isWin

  private def obj(f: (String, Json)*): String = Json.obj(f*).noSpaces
  private def s(v: String): Json              = Json.fromString(v)

  override def beforeAll(): Unit =
    if ready then
      opa.start()
      root = Files.createTempDirectory("qod-opa-dry").toRealPath()
      stack = new AuthzStack(
        "opa_dry",
        20971,
        31371,
        29471,
        20981,
        23260,
        23264,
        filesRoot = Files.createDirectories(root.resolve("files")),
        stackPrefixes = List("opadry_")
      )
      stack.boot()
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(Tenant), "displayName" -> s(Tenant), "metastore" -> Json.obj())
      )
      db = stack
        .post(
          "/api/database/create",
          obj(
            "tenant"   -> s(Tenant),
            "name"     -> s("lake"),
            "kind"     -> s("ducklake"),
            "dataPath" -> s(s"${root.resolve("lake_data")}/")
          )
        )
        .hcursor
        .get[String]("name")
        .fold(e => throw e, identity)
      stack.catalogDatabases += db
      stack.post(
        "/api/pool/create",
        s"""{"tenant":"$Tenant","tenantDb":"$db","pool":"p","size":1,
           |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
      )
      // The default memberships; the tenant goes to OPA below, so OPA alone decides for ana.
      stack.post(
        "/api/user/create",
        obj("tenant" -> s(Tenant), "username" -> s("ana"), "password" -> s(Pw), "kind" -> s("user"))
      )
      stack.await("the pool serving", 120.seconds) {
        stack.flight("SELECT 1", "admin", "admin", Tenant, "p", superuser = true).toOption
      }
      List("CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1), (2)").foreach { sql =>
        stack
          .flight(sql, "admin", "admin", Tenant, "p", superuser = true)
          .fold(e => throw new IllegalStateException(s"$sql: $e"), identity)
      }
      stack.post(
        "/api/tenant/setAcl",
        obj(
          "name"   -> s(Tenant),
          "mode"   -> s("opa"),
          "opaUrl" -> s(s"http://127.0.0.1:${opa.port()}")
        )
      )

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      Try(opa.stop())
      if root != null then
        Try {
          val tree = Files.walk(root)
          try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
          finally tree.close()
        }

  /** OPA answers every statement question with `body` (HTTP `status`) and allows every connect. */
  private def stub(body: String, status: Int = 200): Unit =
    opa.resetAll()
    opa.stubFor(
      post(urlEqualTo(StatementPath)).willReturn(
        aResponse()
          .withStatus(status)
          .withHeader("Content-Type", "application/json")
          .withBody(body)
      )
    )
    opa.stubFor(post(urlEqualTo(ConnectPath)).willReturn(okJson("""{"result":{"allow":true}}""")))

  /** The statement questions OPA got since the last [[stub]]. */
  private def asked: List[Json] =
    opa
      .findAll(postRequestedFor(urlEqualTo(StatementPath)))
      .asScala
      .toList
      .map(r => parse(r.getBodyAsString).fold(e => throw e, identity))

  /** A statement dry run of `sql` for ana on pool p, as the seeded admin: (status, body). */
  private def dryRun(sql: String): (Int, String) =
    stack.postStatus(
      "/api/tenant/opaTest",
      obj("tenant" -> s(Tenant), "pool" -> s("p"), "user" -> s("ana"), "sql" -> s(sql))
    )

  private def answered(sql: String): HCursor =
    val (code, body) = dryRun(sql)
    withClue(body)(code shouldBe 200)
    parse(body).fold(e => throw e, identity).hcursor

  private def error(sql: String): (Int, String, String) =
    val (code, body) = dryRun(sql)
    (
      code,
      parse(body).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(""),
      body
    )

  /** The rows of t, read by the superuser (whom OPA is never asked about). */
  private def count: List[String] =
    stack
      .flight("SELECT count(*) FROM t", "admin", "admin", Tenant, "p", superuser = true)
      .fold(e => fail(e), identity)

  private def pool(): HCursor =
    val r = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(2))
      .build()
      .send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${stack.restPort}/api/pool/list"))
          .header("X-API-Key", stack.login(None, "admin", "admin"))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    parse(r.body()).toOption
      .flatMap(_.hcursor.downField("pools").focus.flatMap(_.asArray))
      .getOrElse(Vector.empty)
      .map(_.hcursor)
      .find(c => c.get[String]("tenant") == Right(Tenant) && c.get[String]("pool") == Right("p"))
      .getOrElse(fail("pool p is not listed"))

  "a statement dry run" should "answer allow with the exact input OPA got, and run nothing" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":true},"decision_id":"d-allow"}""")
    val c = answered("INSERT INTO t VALUES (3)")
    c.get[String]("rule") shouldBe Right("statement")
    c.get[String]("outcome") shouldBe Right("allow")
    c.get[String]("decisionId") shouldBe Right("d-allow")
    // One question, from the node's decision (the gateway asked none), shown word for word.
    asked should have size 1
    c.downField("input").focus shouldBe Some(asked.head)
    val in = c.downField("input").downField("input")
    in.downField("client").get[String]("edge") shouldBe Right("node")
    in.downField("user").get[String]("name") shouldBe Right("ana")
    in.downField("statement").get[String]("class") shouldBe Right("WRITE")
    in.downField("statement").downField("text").succeeded shouldBe false
    val access = in
      .downField("accesses")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .map(_.hcursor)
      .find(_.get[String]("table") == Right("t"))
      .getOrElse(fail(s"no access to t in ${in.focus}"))
    access.get[String]("catalog") shouldBe Right(db.toLowerCase(Locale.ROOT))
    access.get[String]("verb") shouldBe Right("write")
    // OPA allowed the write, and it still never ran.
    count shouldBe List("2")
  }

  it should "answer deny with OPA's reason, and run nothing" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":false,"reason":"no deletes"},"decision_id":"d-deny"}""")
    val c = answered("DELETE FROM t")
    c.get[String]("outcome") shouldBe Right("deny")
    c.get[String]("reason") shouldBe Right("no deletes")
    c.get[String]("decisionId") shouldBe Right("d-deny")
    count shouldBe List("2")
  }

  it should "answer an OPA failure as error, with its cause for a superuser" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("{}", status = 500)
    val c = answered("SELECT id FROM t")
    c.get[String]("outcome") shouldBe Right("error")
    c.get[String]("reason") shouldBe Right("OPA returned HTTP 500")
  }

  it should "ask OPA again for the same statement: no cache answers a dry run" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":true}}""")
    answered("SELECT id FROM t WHERE id > 0").get[String]("outcome") shouldBe Right("allow")
    answered("SELECT id FROM t WHERE id > 0").get[String]("outcome") shouldBe Right("allow")
    // Neither the manager's decision cache nor the node's verdict cache answered the second.
    asked should have size 2
  }

  it should "answer refused_before_opa, asking OPA nothing, when a rule refuses first" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":true}}""")
    val (code, err, body) = error("ATTACH ':memory:' AS x")
    withClue(body) {
      code shouldBe 400
      err shouldBe "refused_before_opa"
    }
    body should include("ATTACH is reserved to superusers")
    asked shouldBe empty
  }

  it should "answer nothing_to_authorize, asking OPA nothing, for a statement on no table" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":true}}""")
    val (code, err, body) = error("SELECT 42")
    withClue(body) {
      code shouldBe 400
      err shouldBe "nothing_to_authorize"
    }
    asked shouldBe empty
  }

  it should "refuse two statements before any node, and admit one holding a semicolon" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":true}}""")
    val (code, err, body) = error("INSERT INTO t VALUES (9); SELECT 1")
    withClue(body) {
      code shouldBe 400
      err shouldBe "single_statement_required"
    }
    asked shouldBe empty
    count shouldBe List("2")
    answered("SELECT ';' AS semi, id FROM t").get[String]("outcome") shouldBe Right("allow")
  }

  "a live statement after dry runs" should "still be judged and run as live" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":true}}""")
    stack.flight("SELECT count(*) FROM t", "ana", Pw, Tenant, "p") shouldBe Right(List("2"))
  }

  "a dry run on a suspended pool" should "answer 409 pool_not_running and leave the pool asleep" in {
    assume(stack != null, "needs the test Postgres and a qod-node build")
    stub("""{"result":{"allow":true}}""")
    stack.post(
      "/api/pool/suspend",
      obj("tenant" -> s(Tenant), "tenantDb" -> s(db), "pool" -> s("p"))
    )
    stack.await("the pool suspended", 60.seconds) {
      val p = pool()
      Option.when(
        p.get[Boolean]("suspended") == Right(true) &&
          p.downField("nodes").focus.flatMap(_.asArray).exists(_.isEmpty)
      )(())
    }
    val (code, err, body) = error("SELECT id FROM t")
    withClue(body) {
      code shouldBe 409
      err shouldBe "pool_not_running"
    }
    asked shouldBe empty
    pool().get[Boolean]("suspended") shouldBe Right(true)
  }
