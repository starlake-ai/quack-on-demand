package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state._
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ColumnPolicyRewriterSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val superuser  = RbacUser(id = "u-super", tenant = None,         username = "root",  role = "admin")
  private val tenantUser = RbacUser(id = "u-1",     tenant = Some("acme"), username = "alice", role = "user")

  private def eff(user: RbacUser, policies: List[RoleColumnPolicy] = Nil): EffectiveSet =
    EffectiveSet(user, Nil, Nil, Nil, Nil, policies)

  private val emptyCat: ColumnCatalog = new ColumnCatalog.MapCatalog(Map.empty)
  private def rw: ColumnPolicyRewriter = new ColumnPolicyRewriter(emptyCat)
  private val ctx = SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))

  "rewrite" should "passthrough for superusers" in {
    rw.rewrite("SELECT c_email FROM customer", StatementKind.Select, eff(superuser), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough when the user has no column policies" in {
    rw.rewrite("SELECT c_email FROM customer", StatementKind.Select, eff(tenantUser, Nil), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough for non-Read statement kinds" in {
    val policies = List(RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer",
                                          "c_email", "mask", Some("'***'")))
    val effSet = eff(tenantUser, policies)
    rw.rewrite("INSERT INTO audit VALUES (1)", StatementKind.Dml,   effSet, ctx).unsafeRunSync() shouldBe Passthrough
    rw.rewrite("CREATE TABLE x(y INT)",        StatementKind.Ddl,   effSet, ctx).unsafeRunSync() shouldBe Passthrough
    rw.rewrite("BEGIN",                        StatementKind.Begin, effSet, ctx).unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough when the SQL fails to parse" in {
    val policies = List(RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer",
                                          "c_email", "mask", Some("'***'")))
    rw.rewrite("SELEC' WRONG", StatementKind.Select, eff(tenantUser, policies), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }

  private val maskEmail = RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer",
                                            "c_email", "mask", Some("'***'"))

  it should "rewrite a direct column reference in the projection to the transform" in {
    val out = rw.rewrite("SELECT c_id, c_email FROM tpch1.customer",
                         StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql              should include ("'***'")
        sql.toLowerCase  should include ("c_id")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "preserve a user-supplied projection alias" in {
    val out = rw.rewrite("SELECT c_email AS e FROM tpch1.customer",
                         StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql               should include ("'***'")
        sql.toLowerCase   should include (" e")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "leave projections that don't touch covered columns alone" in {
    val out = rw.rewrite("SELECT c_id FROM tpch1.customer",
                         StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    // Either Passthrough (nothing changed) OR Rewritten with the same projection. Both are OK
    // for this case as long as the SQL doesn't contain the transform expression.
    out match
      case Passthrough        => succeed
      case Rewritten(sql)     => sql should not include "'***'"
      case Denied(reason)     => fail(s"unexpected deny: $reason")
  }

  // -------- nested SELECTs --------

  it should "rewrite the inner SELECT of a scalar subquery in the projection" in {
    val out = rw.rewrite(
      "SELECT (SELECT c_email FROM tpch1.customer LIMIT 1) AS e FROM tpch1.customer",
      StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include ("'***'")
      case other          => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite a subquery used as a FROM item" in {
    val out = rw.rewrite(
      "SELECT c_email FROM (SELECT c_email FROM tpch1.customer) sub",
      StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) =>
        // Both the outer projection and the inner projection should now reference the mask.
        sql.split("'\\*\\*\\*'").length - 1 should be >= 2
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite each arm of a UNION" in {
    val out = rw.rewrite(
      "SELECT c_email FROM tpch1.customer UNION SELECT c_email FROM tpch1.customer",
      StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.split("'\\*\\*\\*'").length - 1 should be >= 2
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite a CTE body" in {
    val out = rw.rewrite(
      "WITH x AS (SELECT c_email FROM tpch1.customer) SELECT c_email FROM x",
      StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include ("'***'")  // at minimum the CTE body
      case other          => fail(s"expected Rewritten, got $other")
  }