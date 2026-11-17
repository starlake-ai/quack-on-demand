package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.adapter.{QuackError, QuackResponse, TestArrow}
import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.ondemand.api.{CatalogPreviewHandlers, ErrorResponse}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.{RoleColumnPolicy, RolePermission, RoleRowPolicy}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.duckdb.DuckDBResultSet
import org.apache.arrow.vector.ipc.ArrowReader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.{Header, StatusCode}

import java.sql.Connection

/** Discovery fails closed, in the posture of `RbacTenantScopeSpec`: a principal sees exactly what
  * its grants cover, and an object it may not see answers exactly like one that does not exist.
  *
  * The handlers run over the router's real text pipeline (the metadata filter and the column-policy
  * rewriter, via [[PolicyPipelineFixture]]), and the node is an in-process DuckDB holding the
  * tenant catalog, so every listing and probe is answered by DuckDB itself after the pipeline
  * rewrote it. Table grants are qod-node's decision: this node answers a read of an ungranted table
  * with the refusal qod-node sends ([[refusingUngranted]]), so what is pinned here is how the edge
  * answers that refusal; `RestEdgeEndToEndSpec` has the real decision on live nodes. That DuckDB
  * has no DuckLake extension, so the node drops the `AT (VERSION => n)` the edge sends for `asOf*`
  * on the DuckLake kind before executing; what the pipeline does with that clause is pinned by
  * RestTimeTravelPolicySpec.
  */
class RestDiscoveryScopeSpec extends AnyFlatSpec with Matchers:

  import ai.starlake.quack.edge.policy.PolicyPipelineFixture
  import PolicyPipelineFixture.*

  private val Token = "qod_pat_alice"

  private val pat = PatPrincipal(
    user = tenantUser,
    patId = "pat-d",
    scope = SessionScope(superuser = false, manageableTenants = Set.empty),
    isAdmin = false,
    restriction = TokenRestriction.Unrestricted
  )

  private val cfg = RestEdgeConfig(
    enabled = true,
    defaultLimit = 100,
    maxRows = 1000,
    maxResponseBytes = 1L << 20,
    stmtTimeoutSec = 30,
    maxConcurrentRequests = 4
  )

  private val reader: DuckLakeCatalogReader = new DuckLakeCatalogReader(null):
    override def maxSnapshotId(): Option[Long]                        = Some(3L)
    override def snapshotExists(id: Long): Boolean                    = id == 3L
    override def viewEverNamed(schema: String, name: String): Boolean = name == "v_customer"

  private def tenantDbName(kc: KindCase) = s"acme_${kc.suffix}"

  /** `main.customer` (with a sensitive `ssn`), `main.orders`, `main.v_customer`, `sales.leads` and
    * `hr.salaries`, in the tenant catalog.
    */
  private def seed(conn: Connection, cat: String): Unit =
    val c = SqlQuote(cat)
    List(
      s"CREATE SCHEMA $c.sales",
      s"CREATE SCHEMA $c.hr",
      s"CREATE TABLE $c.main.customer (c_id INTEGER, c_email VARCHAR, ssn VARCHAR)",
      s"INSERT INTO $c.main.customer VALUES (1, 'a@x.io', '111'), (2, 'b@x.io', '222')",
      s"CREATE TABLE $c.main.orders (o_id INTEGER)",
      s"CREATE VIEW $c.main.v_customer AS SELECT c_id FROM $c.main.customer",
      s"CREATE TABLE $c.sales.leads (l_id INTEGER)",
      s"CREATE TABLE $c.hr.salaries (s_id INTEGER)",
      s"""CREATE TABLE $c.main.dotted ("a.b" INTEGER, c_email VARCHAR)""",
      s"INSERT INTO $c.main.dotted VALUES (1, 'a@x.io')"
    ).foreach(exec(conn, _))

  private object SqlQuote:
    def apply(s: String): String = "\"" + s + "\""

  private val AtClause  = """ AT \(VERSION => \d+\)""".r
  private val UsePrefix = """(?s)^\s*(USE\s+[^;]+);\s*(.*)$""".r

  /** The in-process node: the router's `USE` prelude runs on its own, then the statement. */
  private def node(conn: Connection): String => QuackResponse = sent =>
    val body = sent match
      case UsePrefix(use, rest) => exec(conn, use); rest
      case other                => other
    try
      val st = conn.createStatement()
      val rs = st.executeQuery(AtClause.replaceAllIn(body, "")).asInstanceOf[DuckDBResultSet]
      QuackResponse.Ok(
        rs.arrowExportStream(TestArrow.sharedAllocator, 1024L).asInstanceOf[ArrowReader],
        1L,
        () => st.close()
      )
    catch case e: java.sql.SQLException => QuackResponse.Failed(QuackError.Permanent(e.getMessage))

  private def grant(cat: String, schema: String, table: String) =
    RolePermission(s"p-$schema-$table", "r-1", cat, schema, table, "RO")

  private val ThreePart = "\"([^\"]+)\"\\.\"([^\"]+)\"\\.\"([^\"]+)\"".r

  /** qod-node's answer to a read of a table no grant covers, in front of `inner`: the edge always
    * names a table by its quoted three-part name, so those are the reads judged. System schemas are
    * the metadata filter's business, as on a real node.
    */
  private def refusingUngranted(
      permissions: List[RolePermission],
      inner: String => QuackResponse
  ): String => QuackResponse = sent =>
    val ungranted = ThreePart
      .findAllMatchIn(sent)
      .map(m => (m.group(1), m.group(2), m.group(3)))
      .filterNot((_, sch, _) => sch == "information_schema" || sch == "pg_catalog")
      .find { (cat, sch, tab) =>
        !permissions.exists(p =>
          (p.catalogName == "*" || p.catalogName == cat) &&
            (p.schemaName == "*" || p.schemaName == sch) &&
            (p.tableName == "*" || p.tableName == tab)
        )
      }
    ungranted match
      case Some((cat, sch, tab)) =>
        QuackResponse.Failed(
          QuackError.Permanent(s"Permission Error: QoD: user:alice lacks grants on $cat.$sch.$tab")
        )
      case None => inner(sent)

  /** The grants: `main.customer` and all of `sales`, nothing on `hr`. */
  private def grants(kc: KindCase) =
    List(grant(kc.catalog, "main", "customer"), grant(kc.catalog, "sales", "*"))

  private val customerColumns = new ColumnCatalog.MapCatalog(
    AllKinds
      .flatMap(kc =>
        List(
          (kc.catalog, "main", "customer") -> List("c_id", "c_email", "ssn"),
          (kc.catalog, "main", "dotted")   -> List("a.b", "c_email")
        )
      )
      .toMap
  )

  private def withHandlers[A](
      kc: KindCase,
      permissions: List[RolePermission] = Nil,
      columnPolicies: List[RoleColumnPolicy] = Nil,
      rowPolicies: List[RoleRowPolicy] = Nil,
      recorded: Option[scala.collection.mutable.ListBuffer[(String, String)]] = None
  )(body: RestEdgeHandlers => A): A =
    withDuckDb(kc.catalog) { conn =>
      seed(conn, kc.catalog)
      val h = harness(
        kc,
        customerColumns,
        metadataFilter = new MetadataFilterRewriter(enabled = true),
        respond = refusingUngranted(permissions, node(conn))
      )
      val eff = effWith(permissions, columnPolicies, rowPolicies)
      def executor(record: Boolean): CatalogPreviewHandlers.PreviewExecutor =
        (caller, key, sql) =>
          h.router.execute(
            caller.connectionId,
            caller.identity,
            key,
            sql,
            effectiveSet = Some(eff),
            preferredNode = caller.preferredNode,
            recordExecution = record,
            patId = caller.patId,
            source = caller.source
          )
      body(
        new RestEdgeHandlers(
          cfg,
          h.sup,
          Map(Token -> pat).get,
          executor(record = true),
          executor(record = false),
          (_, _) => reader,
          (_, _, _) => None,
          recordRefusedProbe = { (caller, key, sql, failure) =>
            h.router.recordRefusedProbe(
              caller.identity,
              key,
              sql,
              Some(eff),
              failure,
              source = caller.source,
              patId = caller.patId
            )
            recorded.foreach(r => r.synchronized(r += ((sql, failure.getClass.getSimpleName))))
          }
        )
      )
    }

  private def req(q: String = "") = RestRequest(List(s"Bearer $Token"), None, q, "rid-d")

  private type Out = Either[(StatusCode, List[Header], ErrorResponse), RestOk]

  private def names(out: Out): List[String] =
    out.fold(
      e => fail(s"expected 200, got $e"),
      ok =>
        parse(ok.body).toOption.get.asArray.get.toList
          .flatMap(_.hcursor.get[String]("name").toOption)
    )

  private def columnsOf(out: Out): List[(String, String)] =
    out.fold(
      e => fail(s"expected 200, got $e"),
      ok =>
        parse(ok.body).toOption.get.hcursor
          .downField("columns")
          .as[List[Json]]
          .toOption
          .get
          .map(c =>
            (c.hcursor.get[String]("name").toOption.get, c.hcursor.get[String]("type").toOption.get)
          )
    )

  private def db(kc: KindCase) = tenantDbName(kc)

  // ---- listings follow the grants -----------------------------------------------------------

  "the listings" should "contain exactly the objects the principal holds a Read-covering grant on, for every kind" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(kc, permissions = grants(kc)) { h =>
          names(h.schemas("acme", db(kc), req()).unsafeRunSync()) shouldBe List("main", "sales")
          names(h.tables("acme", db(kc), "main", req()).unsafeRunSync()) shouldBe List("customer")
          names(h.tables("acme", db(kc), "sales", req()).unsafeRunSync()) shouldBe List("leads")
          columnsOf(h.table("acme", db(kc), "main", "customer", req()).unsafeRunSync())
            .map(_._1) shouldBe List("c_id", "c_email", "ssn")
          h.table("acme", db(kc), "main", "orders", req()).unsafeRunSync().isLeft shouldBe true
        }
      }
  }

  // ---- ungranted is missing -----------------------------------------------------------------

  "an ungranted object and a missing one" should "get byte-identical 404s on every endpoint" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(kc, permissions = grants(kc)) { h =>
          def same(a: Out, b: Out): Unit =
            a.isLeft shouldBe true
            a shouldBe b
            a.left.toOption.get._1 shouldBe StatusCode.NotFound
          same(
            h.table("acme", db(kc), "main", "orders", req()).unsafeRunSync(),
            h.table("acme", db(kc), "main", "no_such_table", req()).unsafeRunSync()
          )
          same(
            h.rows("acme", db(kc), "main", "orders", req()).unsafeRunSync(),
            h.rows("acme", db(kc), "main", "no_such_table", req()).unsafeRunSync()
          )
          same(
            h.tables("acme", db(kc), "hr", req()).unsafeRunSync(),
            h.tables("acme", db(kc), "no_such_schema", req()).unsafeRunSync()
          )
          same(
            h.rows("acme", db(kc), "main", "orders", req()).unsafeRunSync(),
            h.tables("acme", db(kc), "hr", req()).unsafeRunSync()
          )
        }
      }
  }

  // Time travel, the kind of database and the view history are looked at only once the probe at the
  // current state has seen the object: before that, a selector's own answer (410 for an expired
  // snapshot, 422 for an unknown one, 400 on a view or another kind) would tell an invisible object
  // from an absent one.
  "an ungranted object and a missing one" should "get byte-identical 404s under every time-travel selector" in {
    val selectors =
      List("asOf=999999999", "asOf=1", "asOfTs=1970-01-01T00:00:00Z", "asOfTag=nope", "asOf=3")
    for kc <- AllKinds; q <- selectors do
      withClue(s"${kc.kind} $q: ") {
        withHandlers(kc, permissions = grants(kc)) { h =>
          def same(a: Out, b: Out): Unit =
            a.isLeft shouldBe true
            a shouldBe b
            a.left.toOption.get._1 shouldBe StatusCode.NotFound
          for name <- List("orders", "v_customer") do
            same(
              h.rows("acme", db(kc), "main", name, req(q)).unsafeRunSync(),
              h.rows("acme", db(kc), "main", "no_such_table", req(q)).unsafeRunSync()
            )
            same(
              h.table("acme", db(kc), "main", name, req(q)).unsafeRunSync(),
              h.table("acme", db(kc), "main", "no_such_table", req(q)).unsafeRunSync()
            )
        }
      }
  }

  "a probe the node refuses" should "be recorded once, while the client gets the 404" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        val recorded = scala.collection.mutable.ListBuffer.empty[(String, String)]
        withHandlers(kc, permissions = grants(kc), recorded = Some(recorded)) { h =>
          h.rows("acme", db(kc), "main", "orders", req()).unsafeRunSync().left.map(_._1) shouldBe
            Left(StatusCode.NotFound)
          recorded.toList.map(_._2) shouldBe List("AccessDenied")
          recorded.head._1 should include("\"orders\"")
          recorded.clear()
          // A granted read records nothing of its probe.
          h.rows("acme", db(kc), "main", "customer", req()).unsafeRunSync().isRight shouldBe true
          recorded shouldBe empty
        }
      }
  }

  // ---- column and row policies --------------------------------------------------------------

  private val denySsn =
    RoleColumnPolicy("cp-deny", "r-1", "*", "main", "customer", "ssn", "deny", None)
  private val maskEmail =
    RoleColumnPolicy("cp-mask", "r-1", "*", "main", "customer", "c_email", "mask", Some("'***'"))

  private val maskedRows = Right(
    Json.arr(
      Json.obj(
        "c_id"    -> Json.fromInt(1),
        "c_email" -> Json.fromString("***"),
        "ssn"     -> Json.fromString("111")
      ),
      Json.obj(
        "c_id"    -> Json.fromInt(2),
        "c_email" -> Json.fromString("***"),
        "ssn"     -> Json.fromString("222")
      )
    )
  )

  "a masked column" should "be listed and served as an ordinary column" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(kc, permissions = grants(kc), columnPolicies = List(maskEmail)) { h =>
          columnsOf(h.table("acme", db(kc), "main", "customer", req()).unsafeRunSync()) shouldBe
            List("c_id" -> "INTEGER", "c_email" -> "VARCHAR", "ssn" -> "VARCHAR")
          h.rows("acme", db(kc), "main", "customer", req("order=c_id"))
            .unsafeRunSync()
            .map(ok => parse(ok.body).toOption.get) shouldBe maskedRows
        }
      }
  }

  // A text column is filtered and ordered as `CAST(col AS VARCHAR)`, so that a UUID, JSON or ENUM
  // reads as the VARCHAR it is shown as; the mask must reach the column inside the cast too.
  "a masked text column" should "be filtered and ordered on its masked value" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(kc, permissions = grants(kc), columnPolicies = List(maskEmail)) { h =>
          def ids(q: String) =
            h.rows("acme", db(kc), "main", "customer", req(s"select=c_id&$q"))
              .unsafeRunSync()
              .map(ok => parse(ok.body).toOption.get)
          ids("c_email=eq.a@x.io&order=c_id") shouldBe Right(Json.arr())
          ids("c_email=like.a*&order=c_id") shouldBe Right(Json.arr())
          ids("c_email=eq.***&order=c_email,c_id.desc") shouldBe
            Right(
              Json.arr(Json.obj("c_id" -> Json.fromInt(2)), Json.obj("c_id" -> Json.fromInt(1)))
            )
        }
      }
  }

  // ColumnPolicyRewriter refuses any statement that reads a `deny` column, a star included, rather
  // than drop the column from `SELECT *`. The edge's schema probe is therefore refused, fail-closed,
  // and the whole table answers the one 404 of a missing object. Pinned so a rewriter change that
  // starts dropping the column shows up here.
  "a denied column" should "hide its whole table, since the rewriter refuses the probe's star" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(kc, permissions = grants(kc), columnPolicies = List(denySsn)) { h =>
          val missing = h.table("acme", db(kc), "main", "no_such_table", req()).unsafeRunSync()
          h.table("acme", db(kc), "main", "customer", req()).unsafeRunSync() shouldBe missing
          h.rows("acme", db(kc), "main", "customer", req()).unsafeRunSync().left.map(_._1) shouldBe
            Left(StatusCode.NotFound)
        }
      }
  }

  // DuckDB reads `"a.b"` as one name and the policy rewriters' parser as two, so a policy rewrite
  // that writes it is refused (DottedIdentifierScreen). With no `select` the edge reads the whole
  // row as `*`, as its probe does, and writes no such name.
  "a column whose name holds a dot" should "be served to a policy holder reading the whole row" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(
          kc,
          permissions = grant(kc.catalog, "main", "dotted") :: grants(kc),
          columnPolicies = List(maskEmail)
        ) { h =>
          h.rows("acme", db(kc), "main", "dotted", req())
            .unsafeRunSync()
            .map(ok => parse(ok.body).toOption.get) shouldBe
            Right(
              Json.arr(Json.obj("a.b" -> Json.fromInt(1), "c_email" -> Json.fromString("a@x.io")))
            )
        }
      }
  }

  // Policies are scoped per table: with none on `dotted`, its statement is not a policy rewrite,
  // so the caller may name the column in `select` and in a filter.
  it should "be served when named, to a holder whose policies are on other tables" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(
          kc,
          permissions = grant(kc.catalog, "main", "dotted") :: grants(kc),
          columnPolicies = List(maskEmail)
        ) { h =>
          h.rows("acme", db(kc), "main", "dotted", req("select=a.b&a.b=eq.1"))
            .unsafeRunSync()
            .map(ok => parse(ok.body).toOption.get) shouldBe
            Right(Json.arr(Json.obj("a.b" -> Json.fromInt(1))))
        }
      }
  }

  // A column policy on the table itself makes the column rewriter expand the probe's `*`, and it
  // cannot print such a name (JSqlParser splits "a.b" when the expansion is parsed again; see
  // StarExpansion). The probe is refused, fail-closed, and the table answers the one 404 of a
  // missing object, as on every door. Pinned so lifting that limit in the rewriter shows up here.
  it should "hide its table from a holder of a column policy on that table" in {
    val maskDotted =
      RoleColumnPolicy("cp-mask-d", "r-1", "*", "main", "dotted", "c_email", "mask", Some("'***'"))
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(
          kc,
          permissions = grant(kc.catalog, "main", "dotted") :: grants(kc),
          columnPolicies = List(maskDotted)
        ) { h =>
          val missing = h.table("acme", db(kc), "main", "no_such_table", req()).unsafeRunSync()
          h.table("acme", db(kc), "main", "dotted", req()).unsafeRunSync() shouldBe missing
          h.rows("acme", db(kc), "main", "dotted", req()).unsafeRunSync().left.map(_._1) shouldBe
            Left(StatusCode.NotFound)
        }
      }
  }

  // A read at the current state carries no `AT` clause; what the rewriters do with the clause an
  // `asOf*` read carries is pinned by RestTimeTravelPolicySpec.
  "a row policy" should "filter /rows on every kind" in {
    val onlyTwo = RoleRowPolicy("rp-d", "r-1", "*", "main", "customer", "c_id = 2")
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(kc, permissions = grants(kc), rowPolicies = List(onlyTwo)) { h =>
          h.rows("acme", db(kc), "main", "customer", req("select=c_id&order=c_id"))
            .unsafeRunSync()
            .map(ok => parse(ok.body).toOption.get) shouldBe
            Right(Json.arr(Json.obj("c_id" -> Json.fromInt(2))))
        }
      }
  }
