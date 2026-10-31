package ai.starlake.quack.it

import ai.starlake.acl.model.TableRef
import ai.starlake.acl.parser.{TableAccess, Verb}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.*
import ai.starlake.quack.model.{Tenant, TenantAcl}
import ai.starlake.quack.observability.metrics.OpaInstruments
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import scala.sys.process.*
import scala.util.Try

/** Drives the shipped starter policy (examples/opa/qod-authz.rego) through a real `opa run
  * --server` and QoD's own OPA client stack. Cancelled when `opa` is not on PATH, like
  * QuackCompatibilitySpec; nothing is started unless a test runs past its `assume`.
  */
class OpaRealServerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val opaPresent: Boolean = Process("which opa").!(ProcessLogger(_ => ())) == 0
  private val policy: Path        = Paths.get("examples/opa/qod-authz.rego").toAbsolutePath

  private var proc: Option[Process] = None

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private def healthy(port: Int): Boolean =
    Try {
      val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
      val req = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/health")).GET().build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrElse(false)

  /** Started on first use, so a run without `opa` never spawns anything. */
  private lazy val opaPort: Int =
    val port = freePort()
    val data = Files.createTempFile("qod-opa", ".json")
    Files.writeString(
      data,
      """{"qod":{"pool_grants":{"acme":{"analyst":["bi"]}},
        |"table_grants":{"acme":{"analyst":[{"catalog":"tpch","schema":"*","table":"*","verb":"RO"},
        |{"catalog":"lake","schema":"main","table":"v_secure","verb":"RO"}]}},
        |"blocked_locations":{"acme":["s3://acme-etl/restricted/"]}}}""".stripMargin
    )
    proc = Some(
      Process(
        Seq("opa", "run", "--server", "--addr", s"127.0.0.1:$port", policy.toString, data.toString)
      ).run(ProcessLogger(_ => ()))
    )
    val deadline = System.currentTimeMillis() + 10000
    while System.currentTimeMillis() < deadline && !healthy(port) do Thread.sleep(200)
    if !healthy(port) then fail(s"opa did not become healthy on port $port")
    port

  override def afterAll(): Unit = proc.foreach(_.destroy())

  private def authz =
    new OpaAuthorizer(
      OpaConfig(defaultMode = "qod", url = "", timeoutMs = 2000, cacheTtlSec = 0),
      new OpaClient(2000, OpaClient.jdkPost(2000)),
      new OpaDecisionCache(0),
      OpaInstruments.noop
    )

  private def tenant =
    Tenant("acme", acl = TenantAcl(Some("opa"), Some(s"http://127.0.0.1:$opaPort")))

  private val tgt   = OpaTarget("acme", "tpch", "bi", Nil)
  private val alice = OpaUser("alice", List("analyst"), Nil, Map.empty)

  "the starter policy" should "allow connect for a granted role and deny otherwise" in:
    assume(opaPresent, "opa not on PATH")
    authz.connect(tenant, tgt, alice, "u1", "flightsql") shouldBe a[Decision.Allow]
    authz.connect(tenant, tgt.copy(pool = "etl"), alice, "u1", "flightsql") shouldBe
      a[Decision.Deny]

  it should "let a branch pool inherit connect from a granted parent pool" in:
    assume(opaPresent, "opa not on PATH")
    val branch = OpaTarget("acme", "tpch", "__br_ab12cd34", List("bi"))
    authz.connect(tenant, branch, alice, "u1", "flightsql") shouldBe a[Decision.Allow]
    authz.connect(tenant, branch.copy(parentPools = List("etl")), alice, "u1", "flightsql") shouldBe
      a[Decision.Deny]

  it should "allow RO reads and deny writes, naming the denied access" in:
    assume(opaPresent, "opa not on PATH")
    val read  = TableAccess(TableRef("tpch", "main", "orders"), Verb.Read)
    val write = TableAccess(TableRef("tpch", "main", "orders"), Verb.Write)
    authz.statement(tenant, tgt, alice, "u1", "flightsql", "READ", Set(read), "") shouldBe
      a[Decision.Allow]
    authz.statement(tenant, tgt, alice, "u1", "flightsql", "WRITE", Set(read, write), "") match
      case Decision.Deny(_, denied, _) => denied shouldBe Set(write)
      case other                       => fail(other.toString)

  it should "deny a read outside the granted catalog" in:
    assume(opaPresent, "opa not on PATH")
    val other = TableAccess(TableRef("sales", "main", "orders"), Verb.Read)
    authz.statement(tenant, tgt, alice, "u1", "flightsql", "READ", Set(other), "") shouldBe
      a[Decision.Deny]

  // ---- node decisions (in-engine authorization): OpaAuthorizer.decide, client.edge "node" ----

  it should "judge node accesses on their verbs, whatever their kinds and columns" in:
    assume(opaPresent, "opa not on PATH")
    val read  = OpaAccess("tpch", "main", "orders", "table", Verb.Read, List("o_orderkey"), None)
    val write = read.copy(verb = Verb.Write, columns = Nil)
    authz.decide(tenant, tgt, alice, "u1", "READ", List(read), Nil) shouldBe a[Decision.Allow]
    authz.decide(tenant, tgt, alice, "u1", "WRITE", List(read, write), Nil) match
      case Decision.Deny(_, denied, _) =>
        denied shouldBe Set(TableAccess(TableRef("tpch", "main", "orders"), Verb.Write))
      case other => fail(other.toString)
    val schemaDdl = OpaAccess("tpch", "staging", "*", "schema", Verb.Ddl, Nil, None)
    authz.decide(tenant, tgt, alice, "u1", "DDL", List(schemaDdl), Nil) shouldBe a[Decision.Deny]

  it should "cover a read through a secure view by a read grant on the view alone" in:
    assume(opaPresent, "opa not on PATH")
    val base =
      OpaAccess("lake", "main", "base", "table", Verb.Read, List("id"), Some("lake.main.v_secure"))
    authz.decide(tenant, tgt, alice, "u1", "READ", List(base), Nil) shouldBe a[Decision.Allow]
    authz.decide(
      tenant,
      tgt,
      alice,
      "u1",
      "READ",
      List(base.copy(via = Some("lake.main.v_other"))),
      Nil
    ) shouldBe
      a[Decision.Deny]
    authz.decide(tenant, tgt, alice, "u1", "READ", List(base.copy(via = None)), Nil) shouldBe
      a[Decision.Deny]
    authz.decide(
      tenant,
      tgt,
      alice,
      "u1",
      "READ",
      List(base.copy(via = Some("v_secure"))),
      Nil
    ) shouldBe
      a[Decision.Deny]

  it should "deny a statement naming a blocked location, and allow the others" in:
    assume(opaPresent, "opa not on PATH")
    val read    = OpaAccess("tpch", "main", "orders", "table", Verb.Read, Nil, None)
    val open    = OpaLocation("read", "s3://acme-etl/in/x.csv", "s3://acme-etl/in/", "read_csv")
    val blocked =
      OpaLocation("write", "s3://acme-etl/restricted/out.parquet", "s3://acme-etl/", "copy_to")
    authz
      .decide(tenant, tgt, alice, "u1", "READ", List(read), List(open)) shouldBe a[Decision.Allow]
    authz.decide(tenant, tgt, alice, "u1", "WRITE", List(read), List(open, blocked)) match
      case Decision.Deny(reason, _, _) => reason shouldBe "location not allowed by policy"
      case other                       => fail(other.toString)
    authz.decide(tenant, tgt, alice, "u1", "WRITE", Nil, List(blocked)) shouldBe a[Decision.Deny]
    authz.decide(tenant, tgt, alice, "u1", "READ", Nil, List(open)) shouldBe a[Decision.Allow]

  it should "still judge the gateway's input, which has no via and no locations" in:
    assume(opaPresent, "opa not on PATH")
    val read = TableAccess(TableRef("lake", "main", "base"), Verb.Read)
    authz.statement(tenant, tgt, alice, "u1", "flightsql", "READ", Set(read), "") shouldBe
      a[Decision.Deny]
    val view = TableAccess(TableRef("lake", "main", "v_secure"), Verb.Read)
    authz.statement(tenant, tgt, alice, "u1", "flightsql", "READ", Set(view), "") shouldBe
      a[Decision.Allow]
