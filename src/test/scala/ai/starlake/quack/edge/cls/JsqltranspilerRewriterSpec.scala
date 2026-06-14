package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsqltranspilerRewriterSpec extends AnyFlatSpec with Matchers:

  private val rw = new JsqltranspilerRewriter

  private val maskEmail = RoleColumnPolicy(
    id            = "cp-1",
    roleId        = "r-1",
    catalogName   = "*",
    schemaName    = "tpch1",
    tableName     = "customer",
    columnName    = "c_email",
    action        = "mask",
    transformSql  = Some("'***'")
  )

  private val schema = Map("customer" -> List("c_id", "c_email", "c_phone"))

  "rewrite" should "mask a directly-projected covered column" in {
    val out = rw.rewrite(
      sql            = "SELECT c_email FROM customer",
      schema         = schema,
      policies       = List(maskEmail),
      defaultCatalog = Some("acme_tpch"),
      defaultSchema  = Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside a function call in the projection" in {
    val out = rw.rewrite(
      "SELECT length(c_email) FROM customer",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside CASE WHEN arms" in {
    val out = rw.rewrite(
      "SELECT CASE WHEN c_id > 0 THEN c_email ELSE 'fallback' END FROM customer",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside CAST" in {
    val out = rw.rewrite(
      "SELECT CAST(c_email AS VARCHAR) FROM customer",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside IN list" in {
    val out = rw.rewrite(
      "SELECT c_id FROM customer WHERE c_email IN ('a@x', 'b@x')",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside BETWEEN" in {
    val out = rw.rewrite(
      "SELECT c_id FROM customer WHERE c_email BETWEEN 'a' AND 'z'",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside EXTRACT" in {
    // EXTRACT semantically expects a date/time, but the parser doesn't enforce that. Use it as a
    // smoke test that the visitor descends into the inner expression.
    val out = rw.rewrite(
      "SELECT EXTRACT(YEAR FROM c_email) FROM customer",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside window PARTITION BY" in {
    // jsqltranspiler 1.9's resolver throws NPE on AnalyticExpression with no inner expression
    // (e.g. row_number()), so use sum(c_id) which has an arg the resolver can walk.
    val out = rw.rewrite(
      "SELECT sum(c_id) OVER (PARTITION BY c_email) FROM customer",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside window ORDER BY" in {
    val out = rw.rewrite(
      "SELECT sum(c_id) OVER (ORDER BY c_email) FROM customer",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside LIKE pattern" in {
    val out = rw.rewrite(
      "SELECT c_id FROM customer WHERE c_email LIKE '%@acme.com'",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside row constructor" in {
    val out = rw.rewrite(
      "SELECT (c_id, c_email) FROM customer",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "expand multi-table SELECT * and mask per-table" in {
    val multi = Map(
      "customer" -> List("c_id", "c_email"),
      "orders"   -> List("o_id", "o_customer")
    )
    val out = rw.rewrite(
      "SELECT * FROM customer c JOIN orders o ON c.c_id = o.o_customer",
      multi, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) =>
        sql should include ("'***'")
        sql.toLowerCase should include ("o_id")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "resolve unqualified c_email in a multi-join to the customer table" in {
    val multi = Map(
      "customer" -> List("c_id", "c_email"),
      "orders"   -> List("o_id", "o_customer")
    )
    val out = rw.rewrite(
      "SELECT c_email FROM customer JOIN orders ON c_id = o_customer",
      multi, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }

  it should "resolve CTE column c_email to the base table" in {
    val out = rw.rewrite(
      "WITH x AS (SELECT c_email FROM customer) SELECT c_email FROM x",
      schema, List(maskEmail),
      Some("acme_tpch"), Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include ("'***'")
      case other                          => fail(s"expected Rewritten, got $other")
  }