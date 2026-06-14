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