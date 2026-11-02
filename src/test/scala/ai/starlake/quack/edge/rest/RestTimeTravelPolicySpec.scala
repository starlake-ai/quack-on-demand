package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.edge.sql.PostgresAclValidator
import ai.starlake.quack.ondemand.state.{RoleColumnPolicy, RolePermission, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Do the column- and row-policy rewriters keep `AT (VERSION => n)` on a three-part table reference
  * AND still apply the policy? The edge sends both its schema probe and its data statement with the
  * same `AT` id on a DuckLake tenant-db.
  *
  * The ACL validator and the metadata filter strip the clause before parsing
  * (SqlParser.stripTimeTravelClauses); jsqlparser itself rejects it. The column and row policy
  * rewriters strip it with the validator's own scanner and put it back on the same table reference
  * (TimeTravelCarrier), inside the row-policy wrapper on the base table, so a principal holding a
  * column or row policy can still read at a snapshot with the policy applied. A clause that cannot
  * be pinned to a table reference fails closed.
  */
class RestTimeTravelPolicySpec extends AnyFlatSpec with Matchers:

  import ai.starlake.quack.edge.policy.PolicyPipelineFixture.*

  private val kc  = DuckLake
  private val Ref = s""""${kc.catalog}"."main"."customer""""
  private val At  = "AT (VERSION => 3)"

  private val maskEmail =
    RoleColumnPolicy("cp-1", "r-1", "*", "main", "customer", "c_email", "mask", Some("'***'"))
  private val regionX =
    RoleRowPolicy("rp-1", "r-1", "*", "main", "customer", "c_region = 'X'")

  private val columnCatalog = new ColumnCatalog.MapCatalog(
    Map((kc.catalog, "main", "customer") -> List("c_id", "c_email", "c_region"))
  )

  /** The edge's schema probe and data statement, with or without the snapshot clause. */
  private def probe(at: String) = s"SELECT * FROM $Ref$at LIMIT 0"
  private def data(at: String)  =
    s"""SELECT "c_id", "c_email" FROM $Ref$at WHERE "c_id" >= CAST('2' AS INTEGER) """ +
      """ORDER BY "c_id" DESC LIMIT 101 OFFSET 0"""
  private val WithAt                             = s" $At"
  private val statements: List[String => String] = List(probe, data)

  private def pipeline = harness(kc, columnCatalog)

  "a snapshot read" should "reach the node with the AT clause and the three-part name intact when no policy applies" in
    statements.foreach { st =>
      val sent = pipeline.run(st(WithAt), effWith()).fold(f => fail(f.toString), identity)
      sent should include(s"$Ref $At")
    }

  it should "pass the ACL validator on a table grant: the validator strips the clause before parsing" in {
    val validator = new PostgresAclValidator(tenantCatalogs = _ => Set(kc.catalog))
    val grant     = RolePermission("p-1", "r-1", kc.catalog, "main", "customer", "RO")
    val h         = harness(kc, columnCatalog, validator = validator)
    statements.foreach { st =>
      h.run(st(WithAt), effWith(permissions = List(grant))) match
        case Right(sent) => sent should include(s"$Ref $At")
        case Left(f)     => fail(s"expected admitted, got $f")
    }
  }

  "the policies" should "apply to the same probe and data statement without the AT clause" in
    statements.foreach { st =>
      val masked = pipeline.run(st(""), effWith(columnPolicies = List(maskEmail)))
      masked.fold(f => fail(f.toString), identity) should include("'***'")
      val filtered = pipeline.run(st(""), effWith(rowPolicies = List(regionX)))
      filtered.fold(f => fail(f.toString), identity) should include(
        s"FROM $Ref WHERE (c_region = 'X')"
      )
    }

  it should "filter the edge's data statement by the row policy together with its own filter and order" in
    withDuckDb(kc.catalog) { conn =>
      exec(conn, s"CREATE TABLE $Ref (c_id INTEGER, c_email VARCHAR, c_region VARCHAR)")
      exec(
        conn,
        s"INSERT INTO $Ref VALUES (1, 'a', 'X'), (2, 'b', 'Y'), (3, 'c', 'X'), (4, 'd', 'X'), " +
          "(5, 'e', 'Y')"
      )
      val sent =
        pipeline
          .run(data(""), effWith(rowPolicies = List(regionX)))
          .fold(f => fail(f.toString), identity)
      // Row policy (region X) AND the edge's own c_id >= 2, ordered by c_id DESC.
      rowsOf(conn, sent).map(_.head) shouldBe List("4", "3")
    }

  // The rewriters must carry the clause through: a parse failure here would make FlightSqlRouter
  // refuse every snapshot read by a policy holder (fail-closed).
  "a snapshot read by a policy holder" should "keep AT (VERSION => n) on the three-part reference and still apply the column mask" in
    statements.foreach { st =>
      pipeline.run(st(WithAt), effWith(columnPolicies = List(maskEmail))) match
        case Right(sent) =>
          sent should include(s"$Ref $At")
          sent should include("'***'")
        case Left(f) => fail(s"expected the mask applied at the snapshot, got $f")
    }

  it should "keep AT (VERSION => n) on the base table inside the row-policy wrapper" in
    statements.foreach { st =>
      pipeline.run(st(WithAt), effWith(rowPolicies = List(regionX))) match
        case Right(sent) => sent should include(s"FROM $Ref $At WHERE (c_region = 'X')")
        case Left(f)     => fail(s"expected the row filter applied at the snapshot, got $f")
    }

  it should "keep the clause once through both rewriters stacked, the filter on the base table" in
    statements.foreach { st =>
      val both = effWith(columnPolicies = List(maskEmail), rowPolicies = List(regionX))
      pipeline.run(st(WithAt), both) match
        case Right(sent) =>
          sent should include("'***'")
          sent should include(s"FROM $Ref $At WHERE (c_region = 'X')")
          sent.split(java.util.regex.Pattern.quote(At), -1).length - 1 shouldBe 1
        case Left(f) => fail(s"expected both policies applied at the snapshot, got $f")
    }

  // In-process DuckDB has no DuckLake and cannot run AT; its BINDER refusal, rather than a parser
  // error, is what proves the rewritten clause sits where DuckDB's grammar expects it.
  it should "send a statement DuckDB parses" in
    withDuckDb(kc.catalog) { conn =>
      exec(conn, s"CREATE TABLE $Ref (c_id INTEGER, c_email VARCHAR, c_region VARCHAR)")
      val both = effWith(columnPolicies = List(maskEmail), rowPolicies = List(regionX))
      statements.foreach { st =>
        val sent = pipeline.run(st(WithAt), both).fold(f => fail(f.toString), identity)
        val err  = intercept[java.sql.SQLException](rowsOf(conn, sent))
        err.getMessage should include("does not support time travel")
      }
    }

  it should "still be refused fail-closed when the clause does not follow a table reference" in {
    val derived = s"SELECT * FROM (SELECT * FROM $Ref) d $At"
    pipeline.run(derived, effWith(columnPolicies = List(maskEmail))) match
      case Left(RouterFailure.AccessDenied(reason)) =>
        reason should include("column policy rewrite could not parse statement")
      case other => fail(s"expected a fail-closed CLS refusal, got $other")
    pipeline.run(derived, effWith(rowPolicies = List(regionX))) match
      case Left(RouterFailure.AccessDenied(reason)) =>
        reason should include("row policy rewrite could not parse statement")
      case other => fail(s"expected a fail-closed RLS refusal, got $other")
  }
