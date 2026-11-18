package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Issue #238: a column policy on a table that has a column whose name holds a dot (`"a.b"`).
  *
  * The column rewriter expands `*` to apply the mask. jsqltranspiler before 1.17 expanded such a
  * column as column `b` of a table `a` and printed `a.b`, which DuckDB reads the same way (the
  * statement failed to bind, or read another table's `b`); JSqlParser also splits a lone `"a.b"`
  * when the rewrite is parsed again. The expansion must print it qualified (`dotted."a.b"`), which
  * parses back as that one column. Run through the real policy pipeline and in-process DuckDB, so a
  * regression in either library shows here as the wrong rows.
  */
class DottedColumnPolicySpec extends AnyFlatSpec with Matchers:

  import PolicyPipelineFixture.*

  private val kc    = DuckLake
  private val Ref   = s""""${kc.catalog}"."main"."dotted""""
  private val Other = s""""${kc.catalog}"."main"."a""""

  private def mask(column: String) =
    RoleColumnPolicy("cp-1", "r-1", "*", "main", "dotted", column, "mask", Some("'***'"))

  private val catalog = new ColumnCatalog.MapCatalog(
    Map(
      (kc.catalog, "main", "dotted") -> List("a.b", "x.y.z", "c_email"),
      (kc.catalog, "main", "a")      -> List("b")
    )
  )

  private def withTables(f: (java.sql.Connection, Harness) => Unit): Unit =
    withDuckDb(kc.catalog) { conn =>
      exec(conn, s"""CREATE TABLE $Ref ("a.b" INTEGER, "x.y.z" INTEGER, c_email VARCHAR)""")
      exec(conn, s"INSERT INTO $Ref VALUES (1, 2, 'a@x.io')")
      exec(conn, s"CREATE TABLE $Other (b INTEGER)")
      exec(conn, s"INSERT INTO $Other VALUES (99)")
      f(conn, harness(kc, catalog, guarded = true))
    }

  private def sent(h: Harness, sql: String, column: String = "c_email"): String =
    h.run(sql, effWith(columnPolicies = List(mask(column)))) match
      case Right(text) => text
      case Left(f)     => fail(s"$sql was refused: $f")

  "a star over a table with a dotted column" should "be served, masked, to a holder of a column policy on it" in
    withTables { (conn, h) =>
      // The edge's probe and its data statement.
      sent(h, s"SELECT * FROM $Ref LIMIT 0")
      val text = sent(h, s"SELECT * FROM $Ref")
      // Qualified by the table, in whichever form: `dotted."a.b"` or the name as written.
      text should include regex """dotted"?\."a\.b"""".r
      text should include regex """dotted"?\."x\.y\.z"""".r
      text should not include "\"a\".\"b\""
      rowsOf(conn, text) shouldBe List(List("1", "2", "***"))
    }

  it should "mask the dotted column itself when the policy names it" in
    withTables { (conn, h) =>
      rowsOf(conn, sent(h, s"SELECT * FROM $Ref", column = "a.b")) shouldBe
        List(List("***", "2", "a@x.io"))
    }

  it should "expand an aliased table's star, with or without a qualifier" in
    withTables { (conn, h) =>
      List(s"SELECT * FROM $Ref d", s"SELECT d.* FROM $Ref d").foreach { sql =>
        val text = sent(h, sql)
        withClue(s"$sql -> $text") {
          text should include("d.\"a.b\"")
          rowsOf(conn, text) shouldBe List(List("1", "2", "***"))
        }
      }
      // Excluding the masked column leaves nothing to mask: the statement goes as written.
      rowsOf(conn, sent(h, s"SELECT * EXCLUDE (c_email) FROM $Ref d")) shouldBe List(List("1", "2"))
    }

  it should "keep a join's columns apart when a table named like the dot's head is aliased" in
    withTables { (conn, h) =>
      val text = sent(h, s"SELECT * FROM $Ref d, $Other a")
      rowsOf(conn, text) shouldBe List(List("1", "2", "***", "99"))
    }

  // Unaliased, with a table named `a` beside it: the expansion names the dotted column's own table,
  // so `a.b` never reads table `a`'s `b` in its place.
  it should "read each table's own columns over a join with a table named like the dot's head" in
    withTables { (conn, h) =>
      rowsOf(conn, sent(h, s"SELECT * FROM $Ref, $Other")) shouldBe
        List(List("1", "2", "***", "99"))
    }
