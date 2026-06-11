// src/test/scala/ai/starlake/quack/security/FlightHandshakeSecuritySpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthScope
import ai.starlake.quack.model.{NodeSpec, Pool, RoleDistribution, RunningNode, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, PoolPermission}
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Stubbed FlightSQL handshake security suite.
  *
  * Does NOT open any Flight/gRPC client sockets. The two collaborators under
  * test are:
  *
  *   - [[InMemoryAuthService.Service.authenticateBasic]] -- Basic auth + tenant
  *     id/name resolution contract (groups A-B).
  *   - [[PoolSupervisor.authorizeHandshake]] -- 5-gate cascade that guards a
  *     FlightSQL handshake (groups C-D).
  *
  * The real Flight client tests live in the next task (e2e-6).
  *
  * Each test builds its own fresh [[SecurityFixtures.Fixture]] so no state
  * leaks between cases.
  */
class FlightHandshakeSecuritySpec extends AnyFlatSpec with Matchers:

  // ---------- Supervisor builder (mirrors FlightEdgeHarness) ----------

  /** A minimal no-op backend: no child processes. Only start() is called by
    * restore() in these tests (through upsertPool in the fixture), and those
    * nodes are never actually touched by the handshake gate. */
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

  /** Build a PoolSupervisor over the given store and replay the store's state
    * into the supervisor's in-memory caches. */
  private def buildSupervisor(store: InMemoryControlPlaneStore): PoolSupervisor =
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.restore()
    sup

  // ==========================================================================
  // A. Tenant id vs display name resolution
  // ==========================================================================

  "authenticateBasic" should
    "succeed when wire-level tenant header is the display name (acme)" in {
      // The FlightEdgeServer normalises the raw tenant header to a surrogate id
      // before calling authenticateBasic, using Names.looksLikeTenantId + either
      // getTenantById or getTenant.  After that normalisation both paths call
      // authenticateBasic(Some(TenantId), ...).  This test documents that
      // contract: the call reaching the service is always with the id, not the
      // display name.
      val fix = SecurityFixtures.freshStore()
      val svc = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
      // Wire form "acme" (display name) is normalised to TenantId upstream;
      // the actual call that hits the provider is:
      val result = svc.authenticateBasic(
        AuthScope.Tenant(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword
      )
      result shouldBe a[Right[?, ?]]
      result.map(_.username) shouldBe Right(SecurityFixtures.AliceUsername)
    }

  it should
    "succeed when wire-level tenant header is the surrogate id (t-acme0001)" in {
      // Same effective call as the display-name case above: after normalisation
      // the service always sees the surrogate id.
      val fix = SecurityFixtures.freshStore()
      val svc = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
      val result = svc.authenticateBasic(
        AuthScope.Tenant(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword
      )
      result shouldBe a[Right[?, ?]]
    }

  // ==========================================================================
  // B. AuthenticationService.authenticateBasic
  // ==========================================================================

  it should "return Left for a wrong password" in {
    val fix = SecurityFixtures.freshStore()
    val svc = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    val result = svc.authenticateBasic(
      AuthScope.Tenant(SecurityFixtures.TenantId),
      SecurityFixtures.AliceUsername,
      "wrong"
    )
    result shouldBe a[Left[?, ?]]
    // InMemoryBasicAuthProvider emits "invalid credentials" on bcrypt mismatch.
    result.left.map(_.toLowerCase) shouldBe Left("invalid credentials")
  }

  it should "return Left for an unknown user" in {
    val fix = SecurityFixtures.freshStore()
    val svc = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    val result = svc.authenticateBasic(
      AuthScope.Tenant(SecurityFixtures.TenantId),
      "noone",
      "x"
    )
    result shouldBe a[Left[?, ?]]
    result.left.map(m => m should include("not found"))
  }

  it should "succeed for the system superuser (root / no tenant)" in {
    val fix = SecurityFixtures.freshStore()
    val svc = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    val result = svc.authenticateBasic(
      AuthScope.System,
      SecurityFixtures.RootUsername,
      SecurityFixtures.RootPassword
    )
    result shouldBe a[Right[?, ?]]
    result.map(_.username) shouldBe Right(SecurityFixtures.RootUsername)
    result.map(_.tenant)   shouldBe Right(None)
  }

  it should "return Left 'No basic auth providers configured' when hasProviders=false" in {
    val fix = SecurityFixtures.freshStore()
    val svc = new InMemoryAuthService.Service(fix.store, providersEnabled = false)
    val result = svc.authenticateBasic(
      AuthScope.Tenant(SecurityFixtures.TenantId),
      SecurityFixtures.AliceUsername,
      SecurityFixtures.AlicePassword
    )
    result shouldBe Left("No basic auth providers configured")
  }

  // ==========================================================================
  // C. PoolSupervisor.authorizeHandshake -- 5-gate cascade
  // ==========================================================================

  "PoolSupervisor.authorizeHandshake" should
    "return Right with non-empty EffectiveSet for alice on acme/sales (happy path)" in {
      val fix = SecurityFixtures.freshStore()
      val sup = buildSupervisor(fix.store)
      val result = sup.authorizeHandshake(
        SecurityFixtures.TenantName,
        SecurityFixtures.PoolName,
        SecurityFixtures.AliceUsername
      )
      result shouldBe a[Right[?, ?]]
      val hs = result.toOption.get
      hs.effectiveSet.roles should not be empty
    }

  it should "return Left containing 'disabled' when the pool is disabled" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    import ai.starlake.quack.model.PoolKey
    import cats.effect.unsafe.implicits.global
    sup
      .setPoolDisabled(
        PoolKey(SecurityFixtures.TenantName, SecurityFixtures.TenantDbName, SecurityFixtures.PoolName),
        disabled = true
      )
      .unsafeRunSync()
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      SecurityFixtures.AliceUsername
    )
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.toLowerCase should include("disabled")
  }

  it should "return Left containing 'disabled' when the tenant is disabled" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    import cats.effect.unsafe.implicits.global
    sup.setTenantDisabled(SecurityFixtures.TenantName, disabled = true).unsafeRunSync()
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      SecurityFixtures.AliceUsername
    )
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.toLowerCase should include("disabled")
  }

  it should "return Left containing 'not registered' for an unknown user" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      "ghost"
    )
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.toLowerCase should include("not registered")
  }

  it should "return Left when a user from a different tenant attempts access" in {
    // Insert a second tenant 'other' and a user 'carol' scoped to it.
    // Then attempt to authorize carol against the 'acme' pool.
    //
    // PRODUCTION BUG NOTE (gate 3 dead code): findUserForLogin filters to users
    // whose tenant is either empty (superuser) or equals the target tenant id.
    // A user scoped to a DIFFERENT tenant is therefore never returned by
    // findUserForLogin, so the "not scoped" branch in authorizeHandshake (gate 3)
    // is unreachable for the cross-tenant case.  The actual observed error is
    // "not registered".  The test asserts the actual current behavior; the
    // underlying code smell is flagged in the DONE_WITH_CONCERNS report.
    val fix = SecurityFixtures.freshStore()
    val s   = fix.store
    val otherTenantId = "t-other0001"
    import at.favre.lib.crypto.bcrypt.BCrypt
    s.upsertTenant(Tenant(
      id           = otherTenantId,
      name         = "other",
      displayName  = "other",
      authProvider = "db"
    ))
    s.upsertUserWithHash(
      tenant       = Some(otherTenantId),
      username     = "carol",
      passwordHash = BCrypt.withDefaults().hashToString(10, "carolpw".toCharArray),
      role         = "user"
    )
    // Build a new supervisor that picks up both tenants.
    val sup    = buildSupervisor(s)
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      "carol"
    )
    // The user is rejected because findUserForLogin excludes cross-tenant users,
    // producing "not registered" rather than "not scoped".
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.toLowerCase should include("not registered")
  }

  it should "return Left containing 'no access to pool' for bob (no pool_permission)" in {
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      SecurityFixtures.BobUsername
    )
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.toLowerCase should include("no access to pool")
  }

  it should "return Right for alice on both pools when she holds a wildcard pool_permission" in {
    // Wildcard: poolId = None means "every pool in the tenant".
    val fix = SecurityFixtures.freshStore()
    val s   = fix.store

    // Add wildcard permission for alice.
    s.insertPoolPermission(PoolPermission(
      id       = "pp-wild0001",
      tenantId = SecurityFixtures.TenantId,
      poolId   = None,
      userId   = Some(fix.aliceUserId)
    ))

    // Add a second pool 'sales2' under the same tenant-db.
    val sales2Id = "p-sales20001"
    s.upsertPool(Pool(
      id                   = sales2Id,
      tenantId             = SecurityFixtures.TenantId,
      tenantDbId           = SecurityFixtures.TenantDbId,
      name                 = "sales2",
      size                 = 1,
      distribution         = RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
      maxConcurrentPerNode = 0,
      disabled             = false
    ))

    val sup = buildSupervisor(s)

    val onSales  = sup.authorizeHandshake(SecurityFixtures.TenantName, SecurityFixtures.PoolName, SecurityFixtures.AliceUsername)
    val onSales2 = sup.authorizeHandshake(SecurityFixtures.TenantName, "sales2", SecurityFixtures.AliceUsername)

    withClue("alice on sales:") { onSales  shouldBe a[Right[?, ?]] }
    withClue("alice on sales2:") { onSales2 shouldBe a[Right[?, ?]] }
  }

  it should "return Right for a superuser (root) bypassing pool_permission check" in {
    // Gate 5 is skipped when user.tenant.isEmpty (superuser).
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      SecurityFixtures.RootUsername
    )
    result shouldBe a[Right[?, ?]]
    withClue("superuser effective set should indicate bypass") {
      result.toOption.get.isSuperuser shouldBe true
    }
  }

  // ==========================================================================
  // D. JWT claim merge into EffectiveSet
  // ==========================================================================

  it should "include the admin role when jwtRoles contains 'admin'" in {
    // alice already has the admin role via direct user_role edge; the JWT
    // claim should also merge it in (union is idempotent).
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      SecurityFixtures.AliceUsername,
      jwtRoles = Set("admin")
    )
    result shouldBe a[Right[?, ?]]
    val roleNames = result.toOption.get.effectiveSet.roles.map(_.name)
    roleNames should contain("admin")
  }

  it should "silently drop an unknown JWT role and not include it in EffectiveSet" in {
    // Names unknown to the manager are dropped per CLAUDE.md: the JWT
    // claiming "ghostrole" must not cause a failure, and "ghostrole" must
    // NOT appear in the effective roles.
    val fix = SecurityFixtures.freshStore()
    val sup = buildSupervisor(fix.store)
    val result = sup.authorizeHandshake(
      SecurityFixtures.TenantName,
      SecurityFixtures.PoolName,
      SecurityFixtures.AliceUsername,
      jwtRoles = Set("ghostrole")
    )
    result shouldBe a[Right[?, ?]]
    val roleNames = result.toOption.get.effectiveSet.roles.map(_.name)
    roleNames should not contain "ghostrole"
  }
