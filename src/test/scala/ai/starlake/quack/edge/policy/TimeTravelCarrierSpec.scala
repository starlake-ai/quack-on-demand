package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.policy.TimeTravelCarrier.Carry
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import cats.effect.unsafe.implicits.global
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TimeTravelCarrierSpec extends AnyFlatSpec with Matchers:

  /** carry, then restore with no rewrite in between: the clause must land back where it was. */
  private def roundTrip(sql: String): String =
    TimeTravelCarrier.carry(sql) match
      case Carry.Carried(carried, clauses) =>
        TimeTravelCarrier.restore(carried, clauses).getOrElse(fail(s"restore failed: $carried"))
      case other => fail(s"expected Carried, got $other")

  "carry" should "leave a statement without a clause alone" in {
    TimeTravelCarrier.carry("SELECT 'AT (VERSION => 1)' FROM t") shouldBe Carry.Absent
  }

  it should "hand the rewriters text jsqlparser parses, one placeholder per clause" in {
    TimeTravelCarrier.carry(
      "SELECT * FROM a x AT (VERSION => 1) JOIN b AT (VERSION => 2) ON true"
    ) match
      case Carry.Carried(carried, clauses) =>
        clauses shouldBe Vector("AT (VERSION => 1)", "AT (VERSION => 2)")
        noException should be thrownBy CCJSqlParserUtil.parse(carried)
        carried should not include "VERSION"
      case other => fail(s"expected Carried, got $other")
  }

  it should "pin each clause by position across line breaks, tabs and surrogate pairs" in {
    val sql = "SELECT '😀\t' AS e\r\nFROM\ta\tx\rAT (VERSION => 1)\n, b AT (VERSION => 2)"
    roundTrip(sql) shouldBe
      "SELECT '😀\t' AS e FROM a x AT (VERSION => 1), b AT (VERSION => 2)"
  }

  it should "refuse a clause that follows no table reference" in {
    TimeTravelCarrier.carry("SELECT * FROM (SELECT 1) d AT (VERSION => 1)") shouldBe
      Carry.Unplaceable
    TimeTravelCarrier.carry("SELECT * FROM a /* c */ AT (VERSION => 1)") shouldBe
      Carry.Unplaceable
  }

  it should "refuse two clauses on one table reference" in {
    TimeTravelCarrier.carry("SELECT * FROM a AT (VERSION => 1) AT (VERSION => 2)") shouldBe
      Carry.Unplaceable
  }

  it should "refuse text carrying the reserved marker" in {
    TimeTravelCarrier.carry("SELECT '__qod_time_travel_0__' FROM a AT (VERSION => 1)") shouldBe
      Carry.Unplaceable
  }

  // The carrier copies a clause back verbatim, unseen by either policy rewriter. That holds only
  // while DuckDB refuses to read data inside an AT clause; in-process DuckDB (core) binds the clause
  // before it finds out the catalog has no time travel, so the refusal is observable here.
  "DuckDB" should "refuse subqueries and column names inside an AT clause" in {
    Class.forName("org.duckdb.DuckDBDriver")
    scala.util.Using.resource(java.sql.DriverManager.getConnection("jdbc:duckdb:")) { conn =>
      def error(sql: String): String =
        scala.util.Using.resource(conn.createStatement()) { st =>
          intercept[java.sql.SQLException](st.executeQuery(sql)).getMessage
        }
      scala.util.Using.resource(conn.createStatement())(_.execute("CREATE TABLE t (a INTEGER)"))
      error("SELECT * FROM t AT (VERSION => (SELECT max(a) FROM t))") should
        include("AT clause cannot contain subqueries")
      error("SELECT * FROM t AT (TIMESTAMP => (FROM t))") should
        include("AT clause cannot contain subqueries")
      error("SELECT * FROM t AT (VERSION => a)") should include("AT clause cannot contain column")
    }
  }

  "restore" should "fail when a placeholder went missing from the rewritten text" in {
    TimeTravelCarrier.carry("SELECT * FROM a AT (VERSION => 1)") match
      case Carry.Carried(_, clauses) =>
        TimeTravelCarrier.restore("SELECT * FROM a", clauses) shouldBe None
      case other => fail(s"expected Carried, got $other")
  }

  // ---- both rewriters stacked, as the router runs them ----------------------------------------

  private val Ref = "\"acme_lake\".\"main\".\"customer\""
  private val At  = "AT (VERSION => 3)"

  private val stackedEff = EffectiveSet(
    RbacUser("u-1", Some("acme"), "alice", "user"),
    Nil,
    Nil,
    Nil,
    Nil,
    List(
      RoleColumnPolicy("cp-1", "r-1", "*", "main", "customer", "c_email", "mask", Some("'***'"))
    ),
    List(RoleRowPolicy("rp-1", "r-1", "*", "main", "customer", "c_region = 'X'"))
  )

  /** Column rewrite, then row rewrite of its output: the order FlightSqlRouter applies them. */
  private def stacked(sql: String): String =
    val cls = new ColumnPolicyRewriter(
      new ColumnCatalog.MapCatalog(
        Map(("acme_lake", "main", "customer") -> List("c_id", "c_email", "c_region"))
      )
    )
    val masked = cls
      .rewrite(
        sql,
        StatementKind.Select,
        stackedEff,
        ai.starlake.quack.edge.cls.SchemaContext(Some("acme_lake"), Some("main"), Set.empty)
      )
      .unsafeRunSync() match
      case ColumnPolicyRewriter.Rewritten(s) => s
      case other                             => fail(s"column rewrite: $other")
    new RowPolicyRewriter().rewrite(
      masked,
      StatementKind.Select,
      stackedEff,
      ai.starlake.quack.edge.rls.SchemaContext(Some("acme_lake"), Some("main"))
    ) match
      case RowPolicyRewriter.Rewritten(s) => s
      case other                          => fail(s"row rewrite: $other")

  /** A schema probe and a filtered, ordered, paged read, both at one snapshot. */
  private val snapshotReads = List(
    s"SELECT * FROM $Ref $At LIMIT 0",
    s"""SELECT "c_id", "c_email" FROM $Ref $At WHERE "c_id" >= CAST('2' AS INTEGER) """ +
      """ORDER BY "c_id" DESC LIMIT 101 OFFSET 0"""
  )

  "both policy rewriters" should "mask, filter and keep the clause once, on the base table" in
    snapshotReads.foreach { sql =>
      val out = stacked(sql)
      withClue(s"$sql\n -> $out\n") {
        out should include("'***'")
        out should include(s"FROM $Ref $At WHERE (c_region = 'X')")
        out.split(java.util.regex.Pattern.quote(At), -1).length - 1 shouldBe 1
      }
    }

  // In-process DuckDB has no DuckLake and cannot run AT; its binder refusal, rather than a parser
  // error, shows the restored clause sits where DuckDB's grammar expects it.
  they should "produce a statement DuckDB parses" in {
    Class.forName("org.duckdb.DuckDBDriver")
    scala.util.Using.resource(java.sql.DriverManager.getConnection("jdbc:duckdb:")) { conn =>
      def run(sql: String): Unit =
        scala.util.Using.resource(conn.createStatement())(_.execute(sql)): Unit
      run("ATTACH ':memory:' AS acme_lake")
      run(s"CREATE TABLE $Ref (c_id INTEGER, c_email VARCHAR, c_region VARCHAR)")
      run("USE acme_lake.main")
      snapshotReads.foreach { sql =>
        intercept[java.sql.SQLException](run(stacked(sql))).getMessage should
          include("does not support time travel")
      }
    }
  }
