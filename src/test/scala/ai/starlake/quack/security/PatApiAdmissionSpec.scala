// src/test/scala/ai/starlake/quack/security/PatApiAdmissionSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{Pool, RoleDistribution, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.api.{CatalogPreviewHandlers, ExecCaller}
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import cats.effect.IO
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/** End-to-end contract of a PAT presented as the `/api` bearer credential (`X-API-Key` header): the
  * guard admits it exactly as it would the owning user's login session. Pinned here:
  *   - an admin-owned PAT reaches the admin surface;
  *   - a role=user PAT is demoted to the profile allowlist (`403 admin_required` elsewhere), and
  *     the profile handlers resolve its identity, so `/api/profile/usage` answers 200;
  *   - a revoked PAT is anonymous: `401` everywhere, indistinguishable from garbage;
  *   - a tenant-admin PAT carries its owner's tenant scope, so a cross-tenant `?tenant=` path is
  *     `403 tenant_forbidden` (the same perimeter check a session gets);
  *   - a PAT reaches its own PAT-management subtree, now proven THROUGH the guard: creating a child
  *     of itself succeeds (a reversal of what this suite used to pin -- see [[PatHandlers]]'s
  *     scaladoc for why the revocation cascade makes that safe), while everything outside that
  *     subtree stays refused. Full chain behavior is [[PatChainRestSpec]]; this case only proves
  *     the guard's PAT arm is what let the request through in the first place.
  */
class PatApiAdmissionSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodpatadm")

  private val dbName = s"qodpatadm_test_${System.nanoTime()}"

  private var users: UserStore          = null
  private var pats: PatStore            = null
  private var patAuth: PatAuthenticator = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      // Row-only grants, the wiring PatAuthenticator's scaladoc requires: a PAT
      // is bound to one user row and must never fold in a same-named user's grants.
      patAuth = new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind)))
      users.upsertUser(None, SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, "admin")
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        "admin"
      )
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        "user"
      )

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()
    Try(TestPostgres.dropDatabase(dbName))
    ()

  /** Mint a live PAT for `(tenant, username)` directly against the store; returns (patId, raw). */
  private def mintFor(tenant: Option[String], username: String): (String, String) =
    val uid = users
      .userIdOf(tenant, username)
      .getOrElse(fail(s"fixture user $username not found"))
    val (rec, raw) = pats.mint(uid, s"spec-$username", TokenRestriction.Unrestricted, None, 0)
    (rec.id, raw)

  /** The guard admits only a session or a PAT, so reaching a handler with a PAT proves its PAT arm
    * admitted it.
    */
  private def withHarness(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth = Some(patAuth)
    )
    try body(h)
    finally h.shutdown()

  // ------------------------------------------------------------------
  // (a) admin PAT reaches the admin surface
  // ------------------------------------------------------------------

  "a superuser-owned PAT" should "be admitted on the admin surface" in withHarness { h =>
    val (_, raw) = mintFor(None, SecurityFixtures.RootUsername)
    val resp     = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(raw))
    withClue(s"GET /api/pool/list body: ${resp.body()}") {
      resp.statusCode() shouldBe 200
    }
  }

  // ------------------------------------------------------------------
  // (b) role=user PAT: profile allowlist only
  // ------------------------------------------------------------------

  "a role=user PAT" should "reach its own profile surface and nothing else" in withHarness { h =>
    val (_, raw) = mintFor(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)

    val usage = get(h.httpClient, s"${h.baseUrl}/api/profile/usage", apiKey = Some(raw))
    withClue(s"GET /api/profile/usage body: ${usage.body()}") {
      usage.statusCode() shouldBe 200
    }

    val sealedOff = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(raw))
    withClue(s"GET /api/pool/list body: ${sealedOff.body()}") {
      sealedOff.statusCode() shouldBe 403
      errorCode(sealedOff.body()) should contain("admin_required")
    }
  }

  // ------------------------------------------------------------------
  // (c) revoked PAT is anonymous
  // ------------------------------------------------------------------

  "a revoked PAT" should "get 401 everywhere" in withHarness { h =>
    val uid = users
      .userIdOf(None, SecurityFixtures.RootUsername)
      .getOrElse(fail("fixture user root not found"))
    val (rec, raw) = pats.mint(uid, "to-revoke", TokenRestriction.Unrestricted, None, 0)
    pats.revoke(uid, rec.id) should not be empty

    List("/api/pool/list", "/api/profile/usage").foreach { path =>
      val resp = get(h.httpClient, s"${h.baseUrl}$path", apiKey = Some(raw))
      withClue(s"GET $path body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    }
  }

  // ------------------------------------------------------------------
  // (d) tenant scope travels with the PAT
  // ------------------------------------------------------------------

  "a tenant-admin PAT" should "be tenant-confined exactly like its owner's session" in withHarness {
    h =>
      val (_, raw) = mintFor(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)

      // Own tenant: through the guard and the handler.
      val own = get(
        h.httpClient,
        s"${h.baseUrl}/api/user/list?tenant=${SecurityFixtures.TenantId}",
        apiKey = Some(raw)
      )
      withClue(s"own-tenant body: ${own.body()}") {
        own.statusCode() shouldBe 200
      }

      // Another tenant: the perimeter guard's scope check fires.
      val crossed = get(
        h.httpClient,
        s"${h.baseUrl}/api/user/list?tenant=${SecurityFixtures.GlobexTenantId}",
        apiKey = Some(raw)
      )
      withClue(s"cross-tenant body: ${crossed.body()}") {
        crossed.statusCode() shouldBe 403
        errorCode(crossed.body()) should contain("tenant_forbidden")
      }
  }

  // ------------------------------------------------------------------
  // (e) a PAT reaches the PAT-management routes, through the guard's PAT arm
  //
  // This pins a reversal: the class used to refuse every PAT-presented call on
  // these routes outright (403 session_required). It is admitted now because
  // PatStore.revoke cascades over the whole subtree a PAT-minted child sits in,
  // so a stolen token cannot roll forward past its own revocation -- see
  // PatHandlers' scaladoc. What stays refused (never a sibling, never its
  // parent) is PatChainRestSpec's job to pin; this case only proves the guard
  // itself is what let the request through, mirroring (a)-(d) above.
  // ------------------------------------------------------------------

  "a PAT presented on the PAT-management routes" should
    "mint a child of itself, through the guard's PAT arm" in withHarness { h =>
      val (_, raw) = mintFor(None, SecurityFixtures.RootUsername)
      val minted   = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"successor"}""",
        apiKey = Some(raw)
      )
      withClue(s"create-with-pat body: ${minted.body()}") {
        minted.statusCode() shouldBe 200
      }
    }

  // ------------------------------------------------------------------
  // (f) a PAT on the catalog preview runs as its owner, never as superuser
  //
  // Regression: the catalog preview / data-diff / undrop / restore handlers
  // resolved their executor identity through the JWT-only session lookup, so a
  // PAT (admitted by the guard's PAT arm) fell through to the synthetic
  // superuser: no per-table ACL, no column masking, no row filtering, and the
  // token's own restriction (pools, maxRows, branchOnly) dropped.
  // ------------------------------------------------------------------

  "a tenant-admin PAT on the catalog preview" should
    "reach the executor as its owner, with its restriction and pat id" in {
      TestPostgres.ensureReachable()
      val fix = SecurityFixtures.freshStore()
      fix.store.upsertTenantDb(
        TenantDb(
          id = "td-patdl001",
          tenantId = SecurityFixtures.TenantId,
          name = "acme_patdl",
          kind = TenantDbKind.DuckLake,
          metastore = Map(
            "pgHost"     -> "127.0.0.1",
            "pgPort"     -> "5432",
            "pgUser"     -> "u",
            "pgPassword" -> "p",
            "dbName"     -> "acme_patdl",
            "schemaName" -> "main"
          ),
          dataPath = "/tmp/qod-pat-preview-test"
        )
      )
      fix.store.upsertPool(
        Pool(
          id = "p-patdl001",
          tenantId = SecurityFixtures.TenantId,
          tenantDbId = "td-patdl001",
          name = "dlbi",
          size = 1,
          distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1)
        )
      )
      val seen = new AtomicReference[Option[ExecCaller]](None)
      val recording: CatalogPreviewHandlers.PreviewExecutor = (caller, _, _) =>
        IO {
          seen.set(Some(caller))
          Left(RouterFailure.Unavailable("recorded"))
        }
      val h = ManagerServerHarness.boot(
        fix.store,
        patStore = Some(pats),
        patUserOf =
          Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
        patAuth = Some(patAuth),
        previewExecutor = recording
      )
      try
        val uid = users
          .userIdOf(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
          .getOrElse(fail("fixture user alice not found"))
        val restriction = TokenRestriction.Unrestricted.copy(maxRows = Some(3))
        val (rec, raw)  = pats.mint(uid, "preview-pat", restriction, None, 0)
        val resp        = get(
          h.httpClient,
          s"${h.baseUrl}/api/catalog/tenant/${SecurityFixtures.TenantId}/database/acme_patdl" +
            "/schemas/main/tables/region/preview",
          apiKey = Some(raw)
        )
        withClue(s"PAT preview body: ${resp.body()}") {
          val caller = seen.get().getOrElse(fail("the executor was never called"))
          caller.identity shouldBe SecurityFixtures.AliceUsername
          caller.identity should not be CatalogPreviewHandlers.SuperuserIdentity
          caller.system shouldBe false
          caller.patId shouldBe Some(rec.id)
          caller.restriction.maxRows shouldBe Some(3)
        }
      finally h.shutdown()
    }
