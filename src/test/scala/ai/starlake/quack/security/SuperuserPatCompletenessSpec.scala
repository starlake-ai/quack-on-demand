package ai.starlake.quack.security

import ai.starlake.quack.ondemand.api.EndpointModules
import ai.starlake.quack.ondemand.auth.PatAuthenticator
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.AnyEndpoint

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import scala.util.Try

/** A superuser personal access token passes the guard on every non-public endpoint, so removing the
  * static key strands no caller. Handler-level checks (`superuser_required`) are not covered.
  *
  * Every endpoint declared in [[EndpointModules.all]] (the registry the OpenAPI generator and the
  * cookie and tenant-scope completeness specs reflect over, so a new endpoint is covered without
  * touching this file) is called twice: with no credential, and with a superuser PAT. The guard's
  * own refusal is a 401 with no body (a handler's 401 carries its JSON error); an endpoint
  * answering that to the anonymous call is non-public, and the PAT must then get neither a 401 nor
  * a `403 admin_required` or `403 tenant_forbidden` there. The guard decides on the path before any
  * route matches, so a declared endpoint this harness does not mount (federation, branches, the
  * Iceberg views) still exercises it, and answers 404 once the guard admits the token. No request
  * carries a body: a body the guard refuses unread would desynchronize the reused HTTP/1.1
  * connection.
  *
  * The PAT store is real Postgres (`qodstate_pat` has an FK to `qodstate_user`); cancelled when it
  * is unreachable.
  */
class SuperuserPatCompletenessSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodpatall")

  private val dbName = s"qodpatall_test_${System.nanoTime()}"

  private var users: UserStore = null
  private var pats: PatStore   = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      // The PAT's owner: the harness logs in against the in-memory fixture, the PAT handler
      // mints against this database (the qodstate_pat FK is on its user row).
      users.upsertUser(None, SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, "admin")

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()
    Try(TestPostgres.dropDatabase(dbName))
    ()

  /** Endpoints that answer a superuser PAT with 401 by design: they read only a session JWT. The
    * static key got the same 401 there, so the PAT loses nothing.
    */
  private val SessionOnly: Set[String] = Set("/api/auth/whoami")

  /** Public, zero-arg vals of `module` whose runtime return type is a tapir Endpoint. */
  private def endpointsOf(module: AnyRef): List[(String, AnyEndpoint)] =
    module.getClass.getMethods.toList
      .filter(_.getParameterCount == 0)
      .filter(m => classOf[sttp.tapir.Endpoint[?, ?, ?, ?, ?]].isAssignableFrom(m.getReturnType))
      .map(m => m.getName -> m.invoke(module).asInstanceOf[AnyEndpoint])

  private val allEndpoints: List[(String, AnyEndpoint)] =
    EndpointModules.all.flatMap(endpointsOf)

  private val CaptureRe = "\\{([^}]+)\\}".r

  /** The endpoint's path with every capture filled: the tenant with the fixture tenant (the
    * perimeter check then runs against a real tenant), any other capture with a filler segment.
    */
  private def concretePath(ep: AnyEndpoint): String =
    CaptureRe.replaceAllIn(
      ep.showPathTemplate().takeWhile(_ != '?'),
      m => if m.group(1) == "tenant" then SecurityFixtures.TenantId else "x"
    )

  private def methodOf(ep: AnyEndpoint): String = ep.method.map(_.method).getOrElse("GET")

  private def call(
      h: ManagerServerHarness.Harness,
      ep: AnyEndpoint,
      credential: Option[String]
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(h.baseUrl + concretePath(ep)))
      .timeout(RequestTimeout)
      .method(methodOf(ep), HttpRequest.BodyPublishers.noBody())
    credential.foreach(c => b.header("X-API-Key", c))
    h.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())

  /** The guard's refusal: 401 with no body. */
  private def guardRefused(r: HttpResponse[String]): Boolean =
    r.statusCode() == 401 && r.body().isEmpty

  "a superuser personal access token" should
    "pass the guard on every non-public endpoint, as the static key did" in {
      TestPostgres.ensureReachable()
      val fix = SecurityFixtures.freshStore()
      val h   = ManagerServerHarness.boot(
        fix.store,
        patStore = Some(pats),
        patUserOf =
          Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
        patAuth =
          Some(new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind))))
      )
      try
        val pat     = h.superuserPat()
        val guarded = allEndpoints.filter((_, ep) => guardRefused(call(h, ep, None)))
        // Without these, a reflection or a probe that found nothing would pass vacuously.
        guarded.size should be > 100
        guarded.map(_._1) should contain allOf (
          "listPools",
          "listTenants",
          "whoami",
          "serviceProviderConfig"
        )
        guarded.map(_._1) should not contain "login"
        val refused = guarded.flatMap { (name, ep) =>
          val path = concretePath(ep)
          val r    = call(h, ep, Some(pat))
          val code = errorCode(r.body())
          if SessionOnly.contains(path) then None
          else if r.statusCode() == 401 || code.exists(Set("admin_required", "tenant_forbidden"))
          then Some(s"$name [${methodOf(ep)} $path] -> ${r.statusCode()} ${code.getOrElse("")}")
          else None
        }
        withClue(s"endpoints refusing a superuser PAT:\n${refused.mkString("\n")}\n") {
          refused shouldBe empty
        }
      finally h.shutdown()
    }
