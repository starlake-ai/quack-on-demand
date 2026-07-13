package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacGroup, RbacRole, RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Tests that [[PostgresAclValidator]] correctly evaluates statements that reference federated
  * table aliases alongside ordinary DuckLake tables. The key property under test: the validator's
  * wildcard / verb matching logic is catalog-name-agnostic, so a federated alias (e.g. `fedpg`) is
  * treated identically to any other catalog name. No code change is required in the validator for
  * federated access control.
  */
class PostgresAclValidatorSpec extends AnyFlatSpec with Matchers:

  // ---- helpers -------------------------------------------------------

  private def perm(catalog: String, schema: String, table: String, verb: String): RolePermission =
    RolePermission(
      id = s"rp-$catalog-$schema-$table-$verb",
      roleId = "role-1",
      catalogName = catalog,
      schemaName = schema,
      tableName = table,
      verb = verb,
      grantedAt = Some(Instant.now())
    )

  private def effectiveWith(permissions: List[RolePermission]): EffectiveSet =
    EffectiveSet(
      user = RbacUser(
        id = "u-1",
        tenant = Some("t-1"),
        username = "alice",
        role = "analyst",
        createdAt = Some(Instant.now()),
        updatedAt = Some(Instant.now())
      ),
      roles = List(
        RbacRole(
          id = "role-1",
          tenantId = "t-1",
          name = "analyst",
          createdAt = Some(Instant.now())
        )
      ),
      groups = Nil,
      permissions = permissions,
      poolPerms = Nil
    )

  private def mkCtx(sql: String, eff: EffectiveSet): ValidationContext =
    ValidationContext(
      username = "alice",
      database = "t-1/td-1/p-1",
      statement = sql,
      peer = "conn-1",
      defaultDatabase = Some("tpch"),
      defaultSchema = Some("main"),
      effectiveSet = Some(eff)
    )

  private val validator = new PostgresAclValidator()

  // ---- tests ---------------------------------------------------------

  "PostgresAclValidator" should "allow SELECT touching only a federated alias when grant exists" in {
    val eff = effectiveWith(permissions = List(perm("fedpg", "public", "orders", "RO")))
    val ctx = mkCtx("SELECT * FROM fedpg.public.orders", eff)
    validator.validate(ctx) shouldBe Allowed
  }

  it should "deny SELECT against a federated alias when no grant exists" in {
    val eff = effectiveWith(permissions = Nil)
    val ctx = mkCtx("SELECT * FROM fedpg.public.orders", eff)
    validator.validate(ctx) match
      case Denied(msg, _) => msg should include("fedpg")
      case other          => fail(s"expected Denied, got $other")
  }

  it should "allow SELECT joining DuckLake + federated alias when both grants exist" in {
    val eff = effectiveWith(permissions =
      List(
        perm("fedpg", "public", "orders", "RO"),
        perm("tpch", "main", "lineitem", "RO")
      )
    )
    val ctx = mkCtx(
      "SELECT o.id, l.qty FROM fedpg.public.orders o JOIN tpch.main.lineitem l ON o.id = l.id",
      eff
    )
    validator.validate(ctx) shouldBe Allowed
  }

  it should "deny the join when only one side is granted" in {
    val eff = effectiveWith(permissions = List(perm("tpch", "main", "lineitem", "RO")))
    val ctx = mkCtx(
      "SELECT o.id, l.qty FROM fedpg.public.orders o JOIN tpch.main.lineitem l ON o.id = l.id",
      eff
    )
    validator.validate(ctx) match
      case Denied(msg, _) => msg should (include("fedpg") or include("orders"))
      case other          => fail(s"expected Denied, got $other")
  }

  it should "allow with catalog-level wildcard on the federated alias" in {
    val eff = effectiveWith(permissions = List(perm("fedpg", "*", "*", "RO")))
    val ctx = mkCtx("SELECT * FROM fedpg.public.orders", eff)
    validator.validate(ctx) shouldBe Allowed
  }

  it should "allow with verb-wildcard (ALL) on the federated table" in {
    val eff = effectiveWith(permissions = List(perm("fedpg", "public", "orders", "ALL")))
    val ctx = mkCtx("SELECT * FROM fedpg.public.orders", eff)
    validator.validate(ctx) shouldBe Allowed
  }

  it should "deny when no EffectiveSet is bound" in {
    val ctx = ValidationContext(
      username = "alice",
      database = "t-1/td-1/p-1",
      statement = "SELECT 1",
      peer = "conn-1",
      defaultDatabase = Some("tpch"),
      defaultSchema = Some("main"),
      effectiveSet = None
    )
    validator.validate(ctx) match
      case Denied(msg, _) => msg should include("no RBAC")
      case other          => fail(s"expected Denied, got $other")
  }

  // ---- two-part name resolution (schema.table under the pool catalog) --
  //
  // Regression for the demo profiles: `FROM tpch1.customer` with the pool's
  // catalog `acme_tpch` attached. Two-part names resolve ANSI-style
  // (schema=tpch1 under the default catalog), so the validator must match
  // the grant `acme_tpch.tpch1.customer`, not a catalog-first guess
  // `tpch1.main.customer`.

  private val catalogAware = new PostgresAclValidator(
    defaultDatabase = "acme_tpch",
    defaultSchema = "main",
    tenantCatalogs = t => if t == "t-1" then Set("acme_tpch") else Set.empty
  )

  private def mkAcmeCtx(sql: String, eff: EffectiveSet): ValidationContext =
    ValidationContext(
      username = "alice",
      database = "t-1/td-1/p-1",
      statement = sql,
      peer = "conn-1",
      defaultDatabase = Some("acme_tpch"),
      defaultSchema = Some("main"),
      effectiveSet = Some(eff)
    )

  it should "resolve a two-part schema.table ref under the pool's default catalog" in {
    val eff = effectiveWith(List(perm("acme_tpch", "tpch1", "customer", "RO")))
    val ctx = mkAcmeCtx(
      "SELECT DISTINCT c_mktsegment, min(c_phone) FROM tpch1.customer GROUP BY 1",
      eff
    )
    catalogAware.validate(ctx) shouldBe Allowed
  }

  it should "cover a two-part schema.table ref via the tenant-admin wildcard" in {
    val eff = effectiveWith(List(perm("*", "*", "*", "ALL")))
    val ctx = mkAcmeCtx("SELECT * FROM tpch1.customer", eff)
    catalogAware.validate(ctx) shouldBe Allowed
  }

  it should "deny a two-part schema.table ref when no grant covers it" in {
    val eff = effectiveWith(permissions = Nil)
    val ctx = mkAcmeCtx("SELECT * FROM tpch1.customer", eff)
    catalogAware.validate(ctx) match
      case Denied(msg, _) => msg should include("acme_tpch.tpch1.customer")
      case other          => fail(s"expected Denied, got $other")
  }

  it should "allow when the principal is a superuser (tenant=None)" in {
    val superuser = RbacUser(
      id = "u-su",
      tenant = None,
      username = "admin",
      role = "admin",
      createdAt = Some(Instant.now()),
      updatedAt = Some(Instant.now())
    )
    val eff = EffectiveSet(
      user = superuser,
      roles = Nil,
      groups = Nil,
      permissions = Nil,
      poolPerms = Nil
    )
    val ctx = mkCtx("SELECT * FROM anything.schema.table", eff)
    validator.validate(ctx) shouldBe Allowed
  }
