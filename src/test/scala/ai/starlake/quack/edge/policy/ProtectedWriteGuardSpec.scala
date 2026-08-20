package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.{ColumnCatalog, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProtectedWriteGuardSpec extends AnyFlatSpec with Matchers:

  import GuardOutcome._

  private val tenantUser =
    RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", role = "user")
  private val superuser =
    RbacUser(id = "u-0", tenant = None, username = "root", role = "admin")

  // customer has c_id (unmasked) and c_email (masked)
  private val maskEmail =
    RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))
  private val rowPolicy =
    RoleRowPolicy("rp-1", "r-1", "*", "tpch1", "customer", "c_region = 'X'")

  private def eff(
      user: RbacUser,
      cols: List[RoleColumnPolicy] = Nil,
      rows: List[RoleRowPolicy] = Nil
  ): EffectiveSet =
    EffectiveSet(user, Nil, Nil, Nil, Nil, cols, rows)

  private val ctx =
    SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))

  // A catalog that knows customer's columns, so SELECT * expands and masks c_email.
  private def guardWithCls(clsOn: Boolean = true, rlsOn: Boolean = true): ProtectedWriteGuard =
    val cat = new ColumnCatalog.MapCatalog(
      Map(("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email", "c_region"))
    )
    new ProtectedWriteGuard(cat, clsEnabled = clsOn, rlsEnabled = rlsOn)

  // A guard whose catalog does NOT know customer, so the Deny-mode oracle cannot resolve it.
  private def guardEmptyCatalog: ProtectedWriteGuard =
    new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(Map.empty),
      clsEnabled = true,
      rlsEnabled = true
    )

  "check" should "allow a write reading only unmasked columns of a CLS table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "deny a write projecting a masked column" in {
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a masked column used only in a WHERE predicate" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer WHERE c_email = 'x@y.z'",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny SELECT * from a masked table (star expands to include c_email)" in {
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT * FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny CREATE VIEW that reads a masked column" in {
    guardWithCls().check(
      "CREATE VIEW tpch1.v AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny ANY read of an RLS-protected table, even unmasked columns" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny MERGE/UPDATE/DELETE reading a masked table (fail-closed, no single inner SELECT)" in {
    guardWithCls().check(
      "UPDATE tpch1.audit SET e = (SELECT c_email FROM tpch1.customer LIMIT 1)",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "allow a write reading a table with no policy" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT o_id FROM tpch1.orders",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "allow bare writes that read no table" in {
    val g = guardWithCls()
    g.check(
      "INSERT INTO tpch1.audit VALUES (1)",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
    g.check(
      "CREATE TABLE tpch1.x (y INT)",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "pass through SELECT (the rewriters handle it)" in {
    guardWithCls().check(
      "SELECT c_email FROM tpch1.customer",
      StatementKind.Select,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "pass through superusers and no-policy principals" in {
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(superuser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser),
      ctx
    ) shouldBe Allow
  }

  it should "not consult column policies when CLS is disabled, nor row policies when RLS is disabled" in {
    // CLS off: the mask is not enforced, so the write is allowed.
    guardWithCls(clsOn = false).check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
    // RLS off: the row policy is not enforced.
    guardWithCls(rlsOn = false).check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe Allow
  }

  it should "deny an unparseable write that a policy-holder submits (fail-closed)" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_email FROM tpch1.customer WHERE ((",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a write reading a CLS table the catalog cannot resolve (Deny-mode oracle)" in {
    guardEmptyCatalog.check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a batch that launders a masked read in a sibling statement" in {
    guardWithCls().check(
      "INSERT INTO tpch1.x SELECT o_id FROM tpch1.orders; " +
        "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "allow a benign multi-statement batch that reads only unmasked columns" in {
    guardWithCls().check(
      "INSERT INTO tpch1.x SELECT o_id FROM tpch1.orders; " +
        "INSERT INTO tpch1.y SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "deny an INSERT VALUES that embeds a subquery reading a masked column" in {
    guardWithCls().check(
      "INSERT INTO tpch1.audit VALUES ((SELECT c_email FROM tpch1.customer LIMIT 1))",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny an INSERT VALUES that embeds a subquery reading an RLS table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.audit VALUES ((SELECT c_id FROM tpch1.customer LIMIT 1))",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a write reading a masked table through a VALUES-derived table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.t SELECT * FROM (VALUES ((SELECT c_email FROM tpch1.customer))) v",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a write reading an RLS table through a VALUES-derived table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.t SELECT * FROM (VALUES ((SELECT c_id FROM tpch1.customer))) v",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  // Subqueries hidden in window / aggregate clauses (arm-preempted / raw-list children).
  private val windowAggForms = List(
    "OVER (ORDER BY (SELECT %s FROM tpch1.customer))"         -> "over order by",
    "OVER (PARTITION BY (SELECT %s FROM tpch1.customer))"     -> "over partition by",
    "FILTER (WHERE b IN (SELECT %s FROM tpch1.customer))"     -> "aggregate filter",
    "WITHIN GROUP (ORDER BY (SELECT %s FROM tpch1.customer))" -> "within group order by"
  )

  windowAggForms.foreach { case (clause, label) =>
    it should s"deny an RLS read hidden in a $label clause" in {
      val sql = s"INSERT INTO tpch1.t SELECT rank() ${clause.format("c_id")} FROM tpch1.s"
      guardWithCls().check(
        sql,
        StatementKind.Dml,
        eff(tenantUser, rows = List(rowPolicy)),
        ctx
      ) shouldBe a[Deny]
    }
  }

  it should "deny a masked read hidden in a window PARTITION BY clause (colHit fires)" in {
    guardWithCls().check(
      "INSERT INTO tpch1.t SELECT rank() OVER (PARTITION BY (SELECT c_email FROM tpch1.customer)) FROM tpch1.s",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }
