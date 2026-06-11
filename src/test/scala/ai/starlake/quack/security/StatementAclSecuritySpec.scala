// src/test/scala/ai/starlake/quack/security/StatementAclSecuritySpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.sql.{
  Allowed,
  Denied,
  PostgresAclValidator,
  ValidationContext
}
import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RolePermission}
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Statement-time ACL gate integration tests.
  *
  * Exercises [[PostgresAclValidator]] fed with [[EffectiveSet]] values produced
  * by [[PoolSupervisor.effectiveSetForUser]], which is the same code path that
  * production uses after a FlightSQL handshake.  No Arrow Flight wire is opened
  * -- the router / Flight layers are skipped per the e2e plan's scope discipline
  * for this task.
  *
  * Each test seeds a fresh [[SecurityFixtures.Fixture]] so there is no state
  * shared between cases.
  */
class StatementAclSecuritySpec extends AnyFlatSpec with Matchers:

  // ---- shared validator (stateless, safe to reuse) --------------------

  /** Wildcard catalog grants are now scoped to the session's tenant. The
    * fixture's tenant `acme` (id `t-acme0001`) owns one tenant-db -- the
    * SecurityFixtures default `acme_main`. The catalog used in the SQL
    * snippets below is the qualified DEFAULT (`acme`), so we include both
    * `acme` and `acme_main` here so the wildcard arm in the validator
    * admits queries that reference either form. */
  private val validator = new PostgresAclValidator(
    defaultDatabase = "acme",
    defaultSchema   = "public",
    tenantCatalogs  = {
      case SecurityFixtures.TenantId => Set("acme", SecurityFixtures.TenantDbName)
      case _                         => Set.empty
    }
  )

  // ---- infrastructure helpers -----------------------------------------

  /** No-op backend -- none of these tests spawn actual nodes. */
  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(
        RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21000,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
      )
    def stop(id: String)    = IO.unit
    def isAlive(id: String) = true
    def discoverExisting()  = IO.pure(Nil)
    def cleanup()           = IO.unit

  /** Build a PoolSupervisor over the given store and replay all persisted state
    * into the supervisor's in-memory caches. */
  private def buildSupervisor(store: InMemoryControlPlaneStore): PoolSupervisor =
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.restore()
    sup

  /** Resolve the effective set for a user that already exists in the fixture. */
  private def effectiveSetFor(sup: PoolSupervisor, userId: String): EffectiveSet =
    sup.effectiveSetForUser(userId).getOrElse(
      fail(s"effectiveSetForUser($userId) returned None -- user missing from store")
    )

  /** Build a ValidationContext aimed at the fixture's pool. */
  private def ctx(
      username:  String,
      sql:       String,
      eff:       EffectiveSet,
      catalog:   String = "acme",
      schema:    String = "public"
  ): ValidationContext =
    ValidationContext(
      username        = username,
      database        = s"${SecurityFixtures.TenantName}/${SecurityFixtures.TenantDbName}/${SecurityFixtures.PoolName}",
      statement       = sql,
      peer            = "test-peer",
      defaultDatabase = Some(catalog),
      defaultSchema   = Some(schema),
      effectiveSet    = Some(eff)
    )

  // =========================================================================
  // A. Superuser bypass
  // =========================================================================

  "root (superuser)" should "be allowed to SELECT any table without a matching grant" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val eff = effectiveSetFor(sup, fix.rootUserId)

    // Superuser has tenant=None; the EffectiveSet produced by authorizeHandshake
    // (and mirrored here by effectiveSetForUser) carries an empty permissions
    // list.  The validator short-circuits on eff.user.tenant.isEmpty -> Allowed.
    eff.user.tenant shouldBe None
    eff.permissions shouldBe empty

    val result = validator.validate(
      ctx("root", "SELECT * FROM other.public.t", eff, catalog = "other")
    )
    result shouldBe Allowed
  }

  // =========================================================================
  // B. Tenant admin grants
  // =========================================================================

  "alice (tenant admin, *.*.* ALL)" should "be allowed to SELECT on her tenant's permitted table" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val eff = effectiveSetFor(sup, fix.aliceUserId)

    // Sanity: alice has the wildcard ALL permission via the admin role.
    eff.permissions.exists(p =>
      p.catalogName == "*" && p.schemaName == "*" && p.tableName == "*" && p.verb == "ALL"
    ) shouldBe true

    validator.validate(
      ctx("alice", "SELECT * FROM acme.public.t", eff)
    ) shouldBe Allowed
  }

  it should "be DENIED on a sibling tenant's catalog (wildcard now tenant-scoped)" in {
    // Regression test for project-catalog-wildcard-cross-tenant: the
    // catalog wildcard `*` no longer matches arbitrary catalogs. It is
    // now scoped to the session's tenant's tenant-db set (passed to the
    // validator via `tenantCatalogs`). Alice's session is bound to
    // `t-acme0001` which owns `acme_main` (+ the `acme` alias used as the
    // default catalog in this spec). `other` is outside that set, so the
    // wildcard arm of `catalogMatch` rejects it. Explicit named grants
    // (`grant.catalogName = "other"`) would still bypass this gate -- the
    // scope only applies to the `*` form.
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val eff = effectiveSetFor(sup, fix.aliceUserId)

    val result = validator.validate(
      ctx("alice", "SELECT * FROM other.public.t", eff, catalog = "other")
    )
    result shouldBe a [Denied]
  }

  it should "be allowed to INSERT on her tenant's table (ALL wildcard covers INSERT)" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val eff = effectiveSetFor(sup, fix.aliceUserId)

    // SqlParser.extract now returns Extracted for INSERT with the target table
    // as a Write access.  The validator extracts allTables = {acme.public.t}
    // and checks each table against INSERT or ALL grants.
    // Alice has *.*.* ALL, which wildcardMatch-covers acme.public.t -> Allowed.
    validator.validate(
      ctx("alice", "INSERT INTO acme.public.t VALUES (1)", eff)
    ) shouldBe Allowed
  }

  // =========================================================================
  // C. Bob has no role memberships
  // =========================================================================

  "bob (tenant user, no memberships)" should "be denied SELECT on any table" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val eff = effectiveSetFor(sup, fix.bobUserId)

    // Bob's EffectiveSet has an empty permissions list.
    eff.permissions shouldBe empty
    eff.roles       shouldBe empty

    val result = validator.validate(
      ctx("bob", "SELECT * FROM acme.public.t", eff)
    )
    result match
      case Denied(msg) =>
        msg.length should be > 0
      case Allowed =>
        fail("expected Denied for bob with no permissions, got Allowed")
  }

  it should "be denied INSERT because he has no INSERT or ALL grant" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val eff = effectiveSetFor(sup, fix.bobUserId)

    // SqlParser.extract now returns Extracted for INSERT with allTables = {acme.public.t}.
    // The validator requires an INSERT or ALL grant covering that table.
    // Bob has no permissions -> unauthorized is non-empty -> Denied.
    val result = validator.validate(
      ctx("bob", "INSERT INTO acme.public.t VALUES (1)", eff)
    )
    result match
      case Denied(msg) =>
        msg.length should be > 0
      case Allowed =>
        fail("expected Denied for bob with no INSERT grant, got Allowed")
  }

  // =========================================================================
  // D. Granular vs wildcard verbs
  // =========================================================================

  /** Replace alice's role permission in the store with an RO-only grant on a
    * specific table, then rebuild the supervisor so the resolver picks it up.
    * Returns a new (store, supervisor, effectiveSet) triple. */
  private def aliceWithReadOnlyGrant(): (InMemoryControlPlaneStore, PoolSupervisor, EffectiveSet) =
    val fix = SecurityFixtures.freshStore()
    // Remove the existing wildcard ALL permission.
    fix.store.deleteRolePermission(SecurityFixtures.AdminPermId)
    // Insert an RO-only permission on acme.public.t.
    fix.store.insertRolePermission(
      RolePermission(
        id          = "rp-ro-only",
        roleId      = SecurityFixtures.AdminRoleId,
        catalogName = "acme",
        schemaName  = "public",
        tableName   = "t",
        verb        = "RO",
        grantedAt   = Some(Instant.now())
      )
    )
    val sup = buildSupervisor(fix.store)
    val eff = effectiveSetFor(sup, fix.aliceUserId)
    (fix.store, sup, eff)

  "alice with RO-only grant on acme.public.t" should "be allowed to SELECT that table" in {
    val (_, _, eff) = aliceWithReadOnlyGrant()

    // Sanity: only the specific RO grant is present.
    eff.permissions.exists(_.verb == "ALL") shouldBe false
    eff.permissions.exists(p =>
      p.verb == "RO" && p.catalogName == "acme" && p.tableName == "t"
    ) shouldBe true

    validator.validate(
      ctx("alice", "SELECT * FROM acme.public.t", eff)
    ) shouldBe Allowed
  }

  it should "be denied INSERT because her RO-only grant does not cover writes" in {
    val (_, _, eff) = aliceWithReadOnlyGrant()

    // SqlParser.extract returns Extracted for INSERT with allTables = {acme.public.t}.
    // The validator requires RW or ALL to cover Verb.Write; RO covers only Verb.Read.
    val result = validator.validate(
      ctx("alice", "INSERT INTO acme.public.t VALUES (1)", eff)
    )
    result match
      case Denied(msg) =>
        msg.length should be > 0
      case Allowed =>
        fail("expected Denied for alice with RO-only grant on INSERT, got Allowed")
  }
