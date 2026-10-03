package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.adapter.{QuackError, QuackResponse, TestArrow}
import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.sql.PostgresAclValidator
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
  * The handlers run over the router's real text pipeline (the real `PostgresAclValidator`, the
  * metadata filter and the column-policy rewriter, via [[PolicyPipelineFixture]]), and the node is
  * an in-process DuckDB holding the tenant catalog, so every listing and probe is answered by
  * DuckDB itself after the pipeline rewrote it. That DuckDB has no DuckLake extension, so the node
  * drops the `AT (VERSION => n)` the edge sends on the DuckLake kind before executing; what the
  * pipeline does with that clause is pinned by RestTimeTravelPolicySpec.
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
    host = "127.0.0.1",
    port = 0,
    tlsEnabled = false,
    tlsCertChain = "",
    tlsPrivateKey = "",
    defaultLimit = 100,
    maxRows = 1000,
    maxResponseBytes = 1L << 20,
    stmtTimeoutSec = 30,
    maxConnections = 4,
    maxHeaderBytes = 16384,
    headerReceiveTimeoutSec = 10,
    idleTimeoutSec = 60
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
      s"CREATE TABLE $c.hr.salaries (s_id INTEGER)"
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

  /** The grants: `main.customer` and all of `sales`, nothing on `hr`. */
  private def grants(kc: KindCase) =
    List(grant(kc.catalog, "main", "customer"), grant(kc.catalog, "sales", "*"))

  private val customerColumns = new ColumnCatalog.MapCatalog(
    AllKinds
      .map(kc => (kc.catalog, "main", "customer") -> List("c_id", "c_email", "ssn"))
      .toMap
  )

  private def withHandlers[A](
      kc: KindCase,
      filteredMetadata: Boolean = true,
      permissions: List[RolePermission] = Nil,
      columnPolicies: List[RoleColumnPolicy] = Nil,
      rowPolicies: List[RoleRowPolicy] = Nil
  )(body: RestEdgeHandlers => A): A =
    withDuckDb(kc.catalog) { conn =>
      seed(conn, kc.catalog)
      val h = harness(
        kc,
        customerColumns,
        validator = new PostgresAclValidator(
          tenantCatalogs = _ => Set(tenantDbName(kc)),
          filteredMetadata = filteredMetadata
        ),
        metadataFilter = new MetadataFilterRewriter(enabled = filteredMetadata),
        respond = node(conn)
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
          (_, _, _) => None
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

  // DuckLake statements always carry `AT (VERSION => n)` (the edge pins a snapshot), which the
  // policy rewriters carry through (TimeTravelCarrier): the policies apply on every kind.
  "a row policy" should "filter /rows on every kind, DuckLake's pinned snapshot included" in {
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

  // ---- filtered metadata off ----------------------------------------------------------------

  "with filtered metadata off" should "404 the listings of a principal without a grant on information_schema" in {
    for kc <- AllKinds do
      withClue(s"${kc.kind}: ") {
        withHandlers(kc, filteredMetadata = false, permissions = grants(kc)) { h =>
          val schemas = h.schemas("acme", db(kc), req()).unsafeRunSync()
          schemas.left.map(_._1) shouldBe Left(StatusCode.NotFound)
          h.tables("acme", db(kc), "main", req()).unsafeRunSync() shouldBe schemas
          // The detail still serves the granted table; only its table-or-view answer is unknown.
          val detail = h.table("acme", db(kc), "main", "customer", req()).unsafeRunSync()
          detail.map(ok => parse(ok.body).toOption.get.hcursor.get[Option[String]]("type")) shouldBe
            Right(Right(None))
        }
      }
  }
