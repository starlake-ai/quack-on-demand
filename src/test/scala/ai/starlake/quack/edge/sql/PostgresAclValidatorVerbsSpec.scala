package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Verb-level coverage tests for [[PostgresAclValidator]].
  *
  * Drives the validator directly with synthetic [[ValidationContext]]s and
  * hand-built [[EffectiveSet]]s -- no Arrow Flight wire, no supervisor stub.
  * Mirrors the construction pattern used in [[PostgresAclValidatorSpec]].
  *
  * The key behaviors under test:
  *   - SELECT allowed iff an RO, RW, or ALL grant covers the table.
  *   - INSERT/UPDATE allowed iff an RW or ALL grant covers the table.
  *   - CREATE TABLE allowed iff a DDL or ALL grant covers the table.
  *   - RO grant does NOT satisfy a Write (INSERT/UPDATE/DELETE) check.
  *   - RW grant does NOT satisfy a DDL check.
  *   - Wildcard ALL on *.*.* satisfies any access.
  *   - COMMIT (ControlFlow) is always admitted regardless of grants.
  *   - Superuser (tenant=None) bypasses the check entirely.
  */
class PostgresAclValidatorVerbsSpec extends AnyFlatSpec with Matchers:

  // ---- helpers -------------------------------------------------------

  private def perm(catalog: String, schema: String, table: String, verb: String): RolePermission =
    RolePermission(
      id          = s"rp-$catalog-$schema-$table-$verb",
      roleId      = "role-1",
      catalogName = catalog,
      schemaName  = schema,
      tableName   = table,
      verb        = verb,
      grantedAt   = Some(Instant.now())
    )

  private def eff(
      username: String,
      tenant:   Option[String],
      perms:    List[RolePermission]
  ): EffectiveSet =
    EffectiveSet(
      user = RbacUser(
        id        = s"u-$username",
        tenant    = tenant,
        username  = username,
        role      = "testrole",
        createdAt = Some(Instant.now()),
        updatedAt = Some(Instant.now())
      ),
      roles = if perms.isEmpty then Nil
              else List(RbacRole(
                id        = "role-1",
                tenantId  = tenant.getOrElse(""),
                name      = "testrole",
                createdAt = Some(Instant.now())
              )),
      groups      = Nil,
      permissions = perms,
      poolPerms   = Nil
    )

  private def ctx(
      sql:     String,
      user:    String,
      effSet:  EffectiveSet,
      catalog: String = "acme",
      schema:  String = "public"
  ): ValidationContext =
    ValidationContext(
      username        = user,
      database        = "t-1/td-1/p-1",
      statement       = sql,
      peer            = "conn-1",
      defaultDatabase = Some(catalog),
      defaultSchema   = Some(schema),
      effectiveSet    = Some(effSet)
    )

  /** Wildcard catalog grants now require the referenced catalog to be in the
    * session's tenant's catalog set. For these tests the session tenant is
    * `t1` and the referenced catalog is `acme`; allow that pair so wildcard
    * tests still pass. The cross-tenant block below uses a different tenant
    * to verify the scope. */
  private val validator = new PostgresAclValidator(
    defaultDatabase = "acme",
    defaultSchema   = "public",
    tenantCatalogs  = {
      case "t1" => Set("acme")
      case _    => Set.empty
    }
  )

  // ---- tests ---------------------------------------------------------

  "PostgresAclValidator" should "deny an unprivileged user's SELECT when no grant exists" in {
    val e = eff("u1", Some("t1"), Nil)
    val result = validator.validate(ctx("SELECT * FROM acme.public.t", "u1", e))
    result match
      case Denied(msg, _) => msg should include("acme")
      case Allowed        => fail("expected Denied for user with no grants, got Allowed")
  }

  it should "allow a SELECT covered by a per-table RO grant" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "t", "RO")))
    validator.validate(ctx("SELECT * FROM acme.public.t", "u1", e)) shouldBe Allowed
  }

  it should "allow an INSERT covered by an RW grant on that table" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "t", "RW")))
    validator.validate(ctx("INSERT INTO acme.public.t VALUES (1)", "u1", e)) shouldBe Allowed
  }

  it should "deny INSERT on a table the user only has RO on" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "t", "RO")))
    val result = validator.validate(ctx("INSERT INTO acme.public.t VALUES (1)", "u1", e))
    result match
      case Denied(msg, _) => msg should (include("acme") or include("Write"))
      case Allowed        => fail("expected Denied for INSERT with RO-only grant, got Allowed")
  }

  it should "allow UPDATE covered by an RW grant" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "t", "RW")))
    validator.validate(
      ctx("UPDATE acme.public.t SET col = 1 WHERE id = 1", "u1", e)
    ) shouldBe Allowed
  }

  it should "deny UPDATE when user only has RO on that table" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "t", "RO")))
    val result = validator.validate(
      ctx("UPDATE acme.public.t SET col = 1 WHERE id = 1", "u1", e)
    )
    result match
      case Denied(_, _) => succeed
      case Allowed      => fail("expected Denied for UPDATE with RO-only grant, got Allowed")
  }

  it should "allow CREATE TABLE covered by a DDL grant" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "newtable", "DDL")))
    validator.validate(
      ctx("CREATE TABLE acme.public.newtable (id INT)", "u1", e)
    ) shouldBe Allowed
  }

  it should "deny CREATE TABLE without a DDL or ALL grant" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "newtable", "RW")))
    val result = validator.validate(
      ctx("CREATE TABLE acme.public.newtable (id INT)", "u1", e)
    )
    result match
      case Denied(_, _) => succeed
      case Allowed      => fail("expected Denied for CREATE without DDL/ALL grant, got Allowed")
  }

  it should "allow SELECT covered by an RW grant (RW includes read)" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "t", "RW")))
    validator.validate(ctx("SELECT * FROM acme.public.t", "u1", e)) shouldBe Allowed
  }

  it should "allow anything for a wildcard ALL on *.*.*" in {
    val e = eff("u1", Some("t1"), List(perm("*", "*", "*", "ALL")))
    validator.validate(ctx("SELECT * FROM acme.public.t", "u1", e)) shouldBe Allowed
    validator.validate(ctx("INSERT INTO acme.public.t VALUES (1)", "u1", e)) shouldBe Allowed
    validator.validate(ctx("CREATE TABLE acme.public.newtable (id INT)", "u1", e)) shouldBe Allowed
  }

  it should "allow ControlFlow statements (COMMIT) without any grant" in {
    val e      = eff("u1", Some("t1"), Nil)
    val result = validator.validate(ctx("COMMIT", "u1", e))
    result shouldBe Allowed
  }

  it should "allow superuser regardless of grants" in {
    // user.tenant = None -> superuser bypass in validate(), bypasses validateForPrincipal
    val superEff = eff("admin", None, Nil)
    validator.validate(ctx("SELECT * FROM acme.public.t", "admin", superEff)) shouldBe Allowed
    validator.validate(ctx("INSERT INTO acme.public.t VALUES (1)", "admin", superEff)) shouldBe Allowed
    validator.validate(ctx("DROP TABLE acme.public.t", "admin", superEff)) shouldBe Allowed
  }

  it should "deny when no EffectiveSet is bound to the context" in {
    val noEffCtx = ValidationContext(
      username        = "u1",
      database        = "t-1/td-1/p-1",
      statement       = "SELECT 1",
      peer            = "conn-1",
      defaultDatabase = Some("acme"),
      defaultSchema   = Some("public"),
      effectiveSet    = None
    )
    validator.validate(noEffCtx) match
      case Denied(msg, _) => msg should include("no RBAC")
      case Allowed        => fail("expected Denied when no EffectiveSet bound, got Allowed")
  }

  it should "allow with catalog-level wildcard grant covering a specific table" in {
    val e = eff("u1", Some("t1"), List(perm("acme", "*", "*", "RO")))
    validator.validate(ctx("SELECT * FROM acme.public.t", "u1", e)) shouldBe Allowed
  }

  it should "deny INSERT...SELECT when only an RW grant on the target exists (source has no RO/RW)" in {
    // INSERT INTO target SELECT * FROM source -- source requires its own
    // Read-covering grant. RW on target covers SELECT+INSERT on target but
    // not SELECT on source.
    val e = eff("u1", Some("t1"), List(perm("acme", "public", "target", "RW")))
    val result = validator.validate(
      ctx("INSERT INTO acme.public.target SELECT * FROM acme.public.source", "u1", e)
    )
    result match
      case Denied(_, _) => succeed
      case Allowed      =>
        fail(
          "expected Denied for INSERT...SELECT when only RW grant on target exists (no Read on source)"
        )
  }

  // ---- catalog-wildcard tenant-scope --------------------------------

  it should "deny wildcard ALL access on a sibling tenant's catalog (cross-tenant scope)" in {
    // Regression for project-catalog-wildcard-cross-tenant. The grant of
    // `*.*.* ALL` no longer matches arbitrary catalogs; it is now scoped
    // to the session's tenant. Tenant `t1` owns `acme` (via the harness
    // tenantCatalogs lookup); the catalog `other` is outside that set.
    val e = eff("u1", Some("t1"), List(perm("*", "*", "*", "ALL")))
    val result = validator.validate(
      ctx("SELECT * FROM other.public.t", "u1", e)
    )
    result match
      case Denied(_, _) => succeed
      case Allowed      =>
        fail("expected Denied for cross-tenant catalog wildcard, got Allowed")
  }

  it should "still admit cross-tenant access via an EXPLICIT catalog grant" in {
    // The scope only constrains the `*` arm of catalogMatch. A grant that
    // names the catalog explicitly (e.g. `other.*.* SELECT`) still bypasses
    // the tenant scope -- operators can deliberately grant cross-tenant
    // access by naming the catalog.
    val e = eff("u1", Some("t1"), List(perm("other", "*", "*", "RO")))
    validator.validate(
      ctx("SELECT * FROM other.public.t", "u1", e)
    ) shouldBe Allowed
  }

  it should "deny wildcard ALL when the session has no allowed catalogs" in {
    // Fail-closed: if a tenant id doesn't resolve in tenantCatalogs (e.g.
    // freshly-deleted tenant, racing with grant lookup), the wildcard arm
    // collapses to deny -- the user gets no implicit access.
    val e = eff("u1", Some("tenant-unknown"), List(perm("*", "*", "*", "ALL")))
    val result = validator.validate(
      ctx("SELECT * FROM acme.public.t", "u1", e)
    )
    result match
      case Denied(_, _) => succeed
      case Allowed      =>
        fail("expected Denied when session tenant has no catalogs, got Allowed")
  }