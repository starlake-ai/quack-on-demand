package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.policy.PolicyPipelineFixture
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state._
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Using}

/** Every reference to a masked column must see the MASKED value, however it is written: bare,
  * through an alias, a table name, `schema.table` or `catalog.schema.table`, any of them quoted or
  * in any case, in any clause. A predicate, ordering or grouping must evaluate against the masked
  * value too: with a partial-information mask (`left(c_email, 3) || '***'`), `WHERE "c_email" LIKE
  * 'abc1%'` matches no row, since no masked value starts with `abc1`.
  *
  * The semantic cases execute in an in-process DuckDB: the rewritten statement over the raw data
  * must return what the ORIGINAL statement returns over a copy of the table holding the masked
  * values. A refusal is accepted only where the case says so (fail closed on a position the mask
  * cannot reach with certainty) or as the router's fail-closed refusal of a statement the column
  * resolver cannot parse.
  */
class ColumnPolicyReferenceSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val Mask    = "left(c_email, 3) || '***'"

  private val user = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val maskEmail =
    RoleColumnPolicy("cp-ref", "r-1", "*", "main", "customer", "c_email", "mask", Some(Mask))
  private def eff(policies: List[RoleColumnPolicy] = List(maskEmail)): EffectiveSet =
    EffectiveSet(user, Nil, Nil, Nil, Nil, policies)

  private val columns = Map(
    (Catalog, "main", "customer") -> List("c_id", "c_email"),
    (Catalog, "main", "orders")   -> List("o_id", "c_id"),
    (Catalog, "main", "probe")    -> List("addr")
  )
  private val rw  = new ColumnPolicyRewriter(new ColumnCatalog.MapCatalog(columns))
  private val ctx = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  private def rewrite(sql: String, policies: List[RoleColumnPolicy] = List(maskEmail)): Outcome =
    rw.rewrite(sql, StatementKind.Select, eff(policies), ctx).unsafeRunSync()

  /** The rewritten text with every inserted mask removed: what is left must not name c_email,
    * except as the output alias a masked projection keeps.
    */
  private def residue(sql: String): String =
    sql
      .replace(s"($Mask)", "")
      .replace(Mask, "")
      .replaceAll("""(?i)\bAS\s+"?c_email"?""", "")

  // ---- rewriter level --------------------------------------------------------------------

  private val tableForms = List(
    "customer",
    "\"customer\"",
    "main.customer",
    "\"main\".\"customer\"",
    s"$Catalog.main.customer",
    s"\"$Catalog\".\"main\".\"customer\"",
    s"\"${Catalog.toUpperCase}\".\"MAIN\".\"Customer\""
  )
  private val qualifierForms = List(
    "",
    "customer.",
    "\"customer\".",
    "\"CUSTOMER\".",
    "main.customer.",
    "\"main\".\"customer\".",
    s"$Catalog.main.customer.",
    s"\"$Catalog\".\"main\".\"customer\"."
  )
  private val columnForms = List("c_email", "\"c_email\"", "\"C_Email\"", "C_EMAIL")

  "a masked column" should "be masked in WHERE through every quoting and qualification" in {
    for
      t <- tableForms
      q <- qualifierForms
      c <- columnForms
    do
      val sql = s"SELECT c_id FROM $t WHERE $q$c LIKE 'abc1%'"
      rewrite(sql) match
        case Rewritten(out) =>
          withClue(s"$sql\n -> $out\n") {
            out should include(Mask)
            residue(out).toLowerCase should not include "c_email"
          }
        case other => fail(s"$sql -> $other")
  }

  it should "be masked through a quoted or unquoted alias" in
    List("c", "\"c\"").foreach { alias =>
      List("c.", "\"c\".", "\"C\".").foreach { q =>
        columnForms.foreach { c =>
          val sql = s"SELECT c_id FROM \"$Catalog\".\"main\".\"customer\" AS $alias " +
            s"WHERE $q$c > 'abc1' ORDER BY $q$c"
          rewrite(sql) match
            case Rewritten(out) =>
              withClue(s"$sql\n -> $out\n")(residue(out).toLowerCase should not include "c_email")
            case other => fail(s"$sql -> $other")
        }
      }
    }

  private val clauseCases = List(
    "SELECT c_id FROM customer WHERE {R} IS NULL",
    "SELECT c_id FROM customer WHERE {R} IS NOT NULL",
    "SELECT c_id FROM customer WHERE ({R}) IS TRUE",
    "SELECT c_id FROM customer WHERE -length({R}) < 0",
    "SELECT c_id FROM customer WHERE {R} COLLATE NOCASE = 'x'",
    "SELECT c_id FROM customer ORDER BY {R}",
    "SELECT count(*) FROM customer GROUP BY {R}",
    "SELECT count(*) FROM customer GROUP BY c_id HAVING max({R}) > 'b'",
    "SELECT p.addr FROM customer JOIN probe p ON p.addr = {R}",
    "SELECT c_id FROM customer QUALIFY row_number() OVER (ORDER BY {R}) = 1",
    "SELECT DISTINCT ON ({R}) c_id FROM customer",
    "SELECT count(*) FILTER (WHERE {R} LIKE 'a%') FROM customer",
    "SELECT string_agg(c_id::VARCHAR, ',' ORDER BY {R}) FROM customer",
    "SELECT o_id FROM orders JOIN customer USING (c_id) WHERE {R} LIKE 'a%'",
    "SELECT c_id FROM customer WHERE EXISTS (SELECT 1 FROM probe p WHERE p.addr = {R})",
    "SELECT c_id FROM customer WHERE {R}::VARCHAR > 'b'"
  )

  it should "be masked or refused in every clause, quoted or not" in
    clauseCases.foreach { template =>
      List("c_email", "\"c_email\"", "\"customer\".\"c_email\"").foreach { r =>
        val sql = template.replace("{R}", r)
        rewrite(sql) match
          case Rewritten(out) =>
            withClue(s"$sql\n -> $out\n")(residue(out).toLowerCase should not include "c_email")
          case Denied(_) => ()
          case other     => fail(s"$sql -> $other")
      }
    }

  it should "refuse a deny-policy column referenced through a quoted identifier in WHERE" in {
    val denyEmail = maskEmail.copy(action = RoleColumnPolicy.ActionDeny, transformSql = None)
    List(
      "SELECT c_id FROM customer WHERE \"c_email\" LIKE 'a%'",
      "SELECT c_id FROM \"main\".\"customer\" WHERE c_email LIKE 'a%'",
      s"SELECT c_id FROM \"$Catalog\".\"main\".\"customer\" ORDER BY \"C_EMAIL\""
    ).foreach { sql =>
      rewrite(sql, List(denyEmail)) shouldBe a[Denied]
    }
  }

  it should "mask or refuse an unqualified reference that two FROM items could own" in {
    val sql = "SELECT 1 FROM customer, (SELECT * FROM probe) p WHERE c_email LIKE 'a%'"
    rewrite(sql) match
      case Rewritten(out) => residue(out).toLowerCase should not include "c_email"
      case Denied(_)      => ()
      case other          => fail(s"expected masked or refused, got $other")
  }

  it should "refuse a NATURAL join that compares a masked column" in {
    rewrite("SELECT c_id FROM customer NATURAL JOIN (SELECT 'abc1' AS c_email) g") shouldBe
      a[Denied]
  }

  it should "not refuse a column name that is declared rather than read" in
    List(
      "WITH x(c_email) AS (SELECT c_email FROM customer) SELECT c_email FROM x",
      "SELECT * EXCLUDE (c_email) FROM customer",
      "SELECT c_email FROM customer UNION ALL SELECT addr FROM probe ORDER BY c_email"
    ).foreach { sql =>
      rewrite(sql) match
        case Denied(reason) => fail(s"$sql refused: $reason")
        case _              => ()
    }

  it should "keep masking a selected column exactly as before" in {
    rewrite("SELECT c_email FROM customer") shouldBe Rewritten(
      s"SELECT $Mask AS c_email FROM customer"
    )
    rewrite("SELECT \"c_email\" FROM \"main\".\"customer\"") match
      case Rewritten(out) => out should include(s"AS \"c_email\"")
      case other          => fail(s"expected Rewritten, got $other")
  }

  it should "keep the time-travel clause on a three-part quoted reference" in {
    val ref = s"\"$Catalog\".\"main\".\"customer\""
    val sql = s"""SELECT "c_id" FROM $ref AT (VERSION => 3) WHERE "c_email" LIKE 'abc1%' """ +
      """ORDER BY "c_email" DESC LIMIT 101 OFFSET 0"""
    rewrite(sql) match
      case Rewritten(out) =>
        out should include(s"$ref AT (VERSION => 3)")
        residue(out).toLowerCase should not include "c_email"
      case other => fail(s"expected Rewritten, got $other")
  }

  // ---- semantic: executed in DuckDB ---------------------------------------------------------

  private val Rows = List(
    (1, Some("abc2@x.io")),
    (2, Some("abc1@y.io")),
    (3, Some("zzz@q.io")),
    (4, None),
    (5, Some("abd@r.io"))
  )

  /** A DuckDB holding `customer` (raw or pre-masked values), `orders` and `probe`. */
  private def database(masked: Boolean): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn                    = DriverManager.getConnection("jdbc:duckdb:")
    def exec(sql: String): Unit = Using.resource(conn.createStatement())(_.execute(sql)): Unit
    exec(s"ATTACH ':memory:' AS $Catalog")
    exec(s"USE $Catalog.main")
    exec("CREATE TABLE customer (c_id INTEGER, c_email VARCHAR)")
    exec("CREATE TABLE orders (o_id INTEGER, c_id INTEGER)")
    exec("CREATE TABLE probe (addr VARCHAR)")
    Rows.foreach { (id, email) =>
      val v = email.fold("NULL")(e => s"'$e'")
      exec(s"INSERT INTO customer VALUES ($id, $v)")
    }
    if masked then exec(s"UPDATE customer SET c_email = $Mask")
    exec("INSERT INTO orders VALUES (10, 1), (11, 2), (12, 3), (13, 5)")
    exec("INSERT INTO probe VALUES ('abc1@y.io'), ('abc***')")
    conn

  private def rowsOf(conn: Connection, sql: String): Either[String, List[List[String]]] =
    Try {
      Using.resource(conn.createStatement()) { st =>
        Using.resource(st.executeQuery(sql)) { rs =>
          val n   = rs.getMetaData.getColumnCount
          val out = ListBuffer.empty[List[String]]
          while rs.next() do out += (1 to n).map(i => rs.getString(i)).toList
          out.toList
        }
      }
    }.toEither.left.map(_.getMessage)

  private val semanticCases: List[(String, Boolean)] = List(
    "SELECT c_id FROM {T} WHERE {R} LIKE 'abc1%' ORDER BY c_id"                            -> false,
    "SELECT c_id FROM {T} WHERE {R} > 'abc1' ORDER BY c_id"                                -> false,
    "SELECT c_id FROM {T} WHERE {R} IS NULL ORDER BY c_id"                                 -> false,
    "SELECT c_id FROM {T} WHERE {R} = 'abc***' ORDER BY c_id"                              -> false,
    "SELECT c_id FROM {T} WHERE starts_with({R}, 'abc1') ORDER BY c_id"                    -> false,
    "SELECT c_id FROM {T} WHERE {R} BETWEEN 'abc1' AND 'abc1~' ORDER BY c_id"              -> false,
    "SELECT c_id FROM {T} WHERE {R} IN (SELECT addr FROM probe) ORDER BY c_id"             -> false,
    "SELECT c_id FROM {T} WHERE NOT ({R} LIKE 'abc1%') ORDER BY c_id"                      -> false,
    "SELECT c_id FROM {T} ORDER BY {R} DESC NULLS LAST, c_id"                              -> false,
    "SELECT count(*) AS n, min(c_id) AS m FROM {T} GROUP BY {R} ORDER BY m"                -> false,
    "SELECT min(c_id) AS m FROM {T} GROUP BY c_id HAVING max({R}) LIKE 'abc1%' ORDER BY m" -> false,
    "SELECT {R} FROM {T} ORDER BY c_id"                                                    -> true,
    "SELECT p.addr FROM {T} JOIN probe p ON p.addr = {R} ORDER BY 1"                       -> true,
    "SELECT c_id FROM {T} QUALIFY row_number() OVER (ORDER BY {R} DESC NULLS LAST, c_id) = 1" ->
      false,
    "SELECT DISTINCT ON ({R}) c_id FROM {T} ORDER BY {R} NULLS LAST, c_id"              -> true,
    "SELECT count(*) FILTER (WHERE {R} LIKE 'abc1%') AS n FROM {T}"                     -> false,
    "SELECT string_agg(c_id::VARCHAR, ',' ORDER BY {R} DESC NULLS LAST, c_id) FROM {T}" -> true,
    "SELECT o_id FROM orders JOIN {T} USING (c_id) WHERE {R} LIKE 'abc1%' ORDER BY 1"   -> true,
    "SELECT c_id FROM {T} WHERE EXISTS (SELECT 1 FROM probe p WHERE p.addr = {R}) ORDER BY 1" ->
      true,
    "SELECT o.o_id FROM orders o WHERE o.c_id IN (SELECT c_id FROM {T} WHERE {R} LIKE 'abc1%')" ->
      false,
    "SELECT c_id, row_number() OVER w AS rn FROM {T} WINDOW w AS (ORDER BY {R} DESC, c_id) " +
      "ORDER BY c_id" -> true
  )

  /** (FROM text, qualifiers valid for it): an unaliased table answers to its name at any
    * qualification, an aliased one only to the alias.
    */
  private val semanticTables: List[(String, List[String])] =
    tableForms.map(_ -> qualifierForms) ++ List(
      s"$Catalog.customer"                           -> List("", "customer.", "\"customer\"."),
      "customer c"                                   -> List("", "c.", "\"c\".", "\"C\"."),
      s"\"$Catalog\".\"main\".\"customer\" AS \"c\"" -> List("", "c.", "\"c\".")
    )

  "a statement reading a masked column" should "return what it returns over the masked values" in
    Using.resources(database(masked = false), database(masked = true)) { (raw, masked) =>
      var checked = 0
      var refused = 0
      for
        (t, quals)       <- semanticTables
        q                <- quals
        c                <- List("c_email", "\"C_Email\"")
        (tpl, mayRefuse) <- semanticCases
      do
        val sql      = tpl.replace("{T}", t).replace("{R}", q + c)
        val expected = rowsOf(masked, sql)
        rewrite(sql) match
          case Rewritten(out) =>
            val actual = rowsOf(raw, out)
            withClue(s"$sql\n -> $out\n") {
              (actual, expected) match
                case (Right(a), Right(e)) => a shouldBe e
                case (Left(_), Left(_))   => () // not valid SQL either way
                case _                    => fail(s"actual $actual, expected $expected")
            }
            checked += 1
          case Denied(_) if mayRefuse || expected.isLeft => ()
          // The router refuses an unparseable statement fail-closed (`catalog.table` defeats the
          // column resolver), so it never reaches a node unmasked.
          case PassthroughParseFailed => refused += 1
          case other => fail(s"$sql -> $other (expected over masked values: $expected)")
      info(s"$checked rewritten statements compared, $refused refused as unparseable")
      checked should be > 1000
    }

  it should "hold for a quoted three-part statement through the whole router pipeline" in {
    import PolicyPipelineFixture.*
    val kc     = DuckLake
    val ref    = s"\"${kc.catalog}\".\"main\".\"customer\""
    val policy =
      maskEmail.copy(transformSql = Some(Mask))
    val h = harness(
      kc,
      new ColumnCatalog.MapCatalog(Map((kc.catalog, "main", "customer") -> List("c_id", "c_email")))
    )
    val sql =
      s"""SELECT "c_id", "c_email" FROM $ref WHERE "c_email" LIKE CAST('abc1%' AS VARCHAR) """ +
        """ORDER BY "c_email" DESC, "c_id" LIMIT 101 OFFSET 0"""
    val sent = h.run(sql, effWith(columnPolicies = List(policy))) match
      case Right(s)               => s
      case Left(f: RouterFailure) => fail(s"expected admitted, got $f")
    withDuckDb(kc.catalog) { conn =>
      exec(conn, s"CREATE TABLE $ref (c_id INTEGER, c_email VARCHAR)")
      exec(conn, s"INSERT INTO $ref VALUES (1, 'abc2@x.io'), (2, 'abc1@y.io'), (3, 'zzz@q.io')")
      // Over the masked values nothing starts with abc1.
      PolicyPipelineFixture.rowsOf(conn, sent) shouldBe Nil
    }
  }
