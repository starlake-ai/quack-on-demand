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

  // v2 needs schema info for the resolver to walk column references. The default catalog
  // covers `customer` with the columns referenced by the masking-exercising tests below.
  // The `Map.empty` case is still tested explicitly via `passthrough SELECT * when the catalog
  // has no entry for the table` which constructs its own rewriter.
  private val defaultCat: ColumnCatalog =
    new ColumnCatalog.MapCatalog(
      Map(("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email", "c_phone", "c_ssn"))
    )
  private def rw: ColumnPolicyRewriter = new ColumnPolicyRewriter(defaultCat)
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

  // -------- SELECT * expansion --------

  private def catWithCustomer(cols: List[String]): ColumnCatalog =
    new ColumnCatalog.MapCatalog(Map(("acme_tpch", "tpch1", "customer") -> cols))

  it should "expand SELECT * via the column catalog and mask covered columns" in {
    val r = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email", "c_phone")))
    val out = r.rewrite("SELECT * FROM tpch1.customer",
                        StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.toLowerCase should include ("c_id")
        sql             should include ("'***'")
        sql.toLowerCase should include ("c_phone")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "expand a qualified t.* against the catalog" in {
    val r = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email")))
    val out = r.rewrite("SELECT c.* FROM tpch1.customer c",
                        StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.toLowerCase should include ("c_id")
        sql             should include ("'***'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "passthrough SELECT * when the catalog has no entry for the table" in {
    val r = new ColumnPolicyRewriter(new ColumnCatalog.MapCatalog(Map.empty))
    r.rewrite("SELECT * FROM tpch1.customer",
              StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync() shouldBe Passthrough
  }

  // -------- deny semantics --------

  private val denySsn = RoleColumnPolicy("cp-2", "r-1", "*", "tpch1", "customer",
                                          "c_ssn", "deny", None)

  it should "deny SELECT c_ssn FROM customer when the policy is deny" in {
    val r = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_ssn")))
    val out = r.rewrite("SELECT c_ssn FROM tpch1.customer",
                        StatementKind.Select, eff(tenantUser, List(denySsn)), ctx).unsafeRunSync()
    out match
      case Denied(reason) => reason.toLowerCase should include ("c_ssn")
      case other          => fail(s"expected Denied, got $other")
  }

  it should "deny SELECT * when expansion uncovers a denied column" in {
    val r = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_ssn")))
    val out = r.rewrite("SELECT * FROM tpch1.customer",
                        StatementKind.Select, eff(tenantUser, List(denySsn)), ctx).unsafeRunSync()
    out match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "rewrite a covered column inside a WHERE predicate" in {
    val r = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email")))
    val out = r.rewrite("SELECT c_id FROM tpch1.customer WHERE c_email LIKE '%@acme.com'",
                        StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include ("'***'")
      case other          => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite covered columns inside composite expressions in projection" in {
    val r = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email")))
    val out = r.rewrite("SELECT length(c_email) FROM tpch1.customer",
                        StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx).unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.toLowerCase should include ("length")
        sql             should include ("'***'")
      case other => fail(s"expected Rewritten, got $other")
  }