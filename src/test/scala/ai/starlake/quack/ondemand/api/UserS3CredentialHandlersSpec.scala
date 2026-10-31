package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant

class UserS3CredentialHandlersSpec extends AnyFlatSpec with Matchers:

  private val Secret = "user-sekr3t-value"
  private val Token  = "user-session-token"

  private final class Fixture(overlap: String => Either[String, Option[String]] = _ => Right(None)):
    val store     = new InMemoryControlPlaneStore()
    val auditRows = new RecordingTelemetryStore
    val sup       = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenant(Tenant("globex")).unsafeRunSync()
    val alice = store.upsertUserWithHash(Some("acme"), "alice", "x", "user")
    val gina  = store.upsertUserWithHash(Some("globex"), "gina", "x", "user")
    val off   = store.upsertUserWithHash(Some("acme"), "off", "x", "user", enabled = false)
    store.upsertUserWithHash(None, "root", "x", "admin")

    private def session(user: String, tenant: Option[String], scope: SessionScope) =
      SessionTokenStore.Session(
        AuthenticatedProfile(user, "user", Set.empty, Map.empty, "db", tenant),
        scope,
        Instant.now()
      )

    private val sessions = Map(
      "alice-tok" -> session("alice", Some("acme"), SessionScope(false, Set.empty)),
      "gina-tok"  -> session("gina", Some("globex"), SessionScope(false, Set.empty)),
      "off-tok"   -> session("off", Some("acme"), SessionScope(false, Set.empty)),
      "admin-tok" -> session("adam", Some("acme"), SessionScope(false, Set("acme"))),
      "root-tok"  -> session("root", None, SessionScope.Superuser)
    )

    val scopeOf: SessionScope.Resolver = t =>
      t.flatMap(sessions.get).fold(SessionScope.NoAccess)(_.scope)

    val h = new UserS3CredentialHandlers(
      sup,
      RestCaller(sessionOf = sessions.get, patOf = _ => None),
      allowedEndpoints = List("http://127.0.0.1:9000"),
      dataFolderOverlap = overlap,
      audit = new AuditRecorder(auditRows, _ => None)
    )

    def add(token: String, req: ProfileS3AddRequest) = h.addMine(req, Some(token)).unsafeRunSync()

    def mine(token: String) = h.listMine(Some(token)).unsafeRunSync()

  private def req(scope: String = "s3://acme-etl/", endpoint: Option[String] = None) =
    ProfileS3AddRequest(scope, "AKIAEXAMPLE", Secret, Some(Token), None, endpoint, None)

  private def code[A](r: Either[(StatusCode, ErrorResponse), A]) =
    r.left.map((s, e) => (s, e.error))

  "addMine" should "store the caller's own credential and never answer the secret or the token" in {
    val f = new Fixture
    val c = f.add("alice-tok", req(endpoint = Some("http://127.0.0.1:9000"))).toOption.get
    c.userId shouldBe f.alice
    c.scope shouldBe "s3://acme-etl/"
    c.secretSet shouldBe true
    c.sessionTokenSet shouldBe true
    c.endpoint shouldBe Some("http://127.0.0.1:9000")
    c.asJson.noSpaces should not include Secret
    c.asJson.noSpaces should not include Token
    val listed = f.mine("alice-tok").toOption.get
    listed.credentials.map(_.id) shouldBe List(c.id)
    listed.asJson.noSpaces should not include Secret
    f.mine("gina-tok").toOption.get.credentials shouldBe Nil
    f.sup.userS3.of(f.alice).map(_.secretAccessKey) shouldBe List(Secret)
  }

  it should "work for a superuser's own row too" in {
    val f = new Fixture
    f.add("root-tok", req("s3://root-etl/")).isRight shouldBe true
    f.mine("root-tok").toOption.get.credentials.map(_.scope) shouldBe List("s3://root-etl/")
  }

  it should "refuse what may not be stored, with the rule's code, writing nothing" in {
    val f = new Fixture
    code(f.add("alice-tok", req("s3://Acme/"))) shouldBe Left(
      (StatusCode.BadRequest, "invalid_scope")
    )
    code(f.add("alice-tok", req(endpoint = Some("evil.example:443")))) shouldBe
      Left((StatusCode.BadRequest, "endpoint_not_allowed"))
    code(f.add("alice-tok", req().copy(secretAccessKey = " "))) shouldBe
      Left((StatusCode.BadRequest, "credential_required"))
    f.mine("alice-tok").toOption.get.credentials shouldBe Nil
  }

  it should "refuse a scope at or under a data folder, and answer 503 when the deny set is unreadable" in {
    val lake = new Fixture(s =>
      Right(Option.when(s.startsWith("s3://lake/acme/"))(s"$s is a database data folder"))
    )
    code(lake.add("alice-tok", req("s3://lake/acme/x/"))) shouldBe
      Left((StatusCode.BadRequest, "scope_in_data_folder"))
    lake.add("alice-tok", req("s3://lake/")).isRight shouldBe true
    val down = new Fixture(_ => Left("the database data folders could not be read"))
    code(down.add("alice-tok", req())) shouldBe
      Left((StatusCode.ServiceUnavailable, "deny_set_unavailable"))
    down.mine("alice-tok").toOption.get.credentials shouldBe Nil
  }

  it should "refuse no credential, an unknown one and a disabled account" in {
    val f = new Fixture
    code(f.h.addMine(req(), None).unsafeRunSync()) shouldBe
      Left((StatusCode.Unauthorized, "no_session_identity"))
    code(f.add("nobody-tok", req())) shouldBe Left((StatusCode.Unauthorized, "no_session_identity"))
    code(f.add("off-tok", req())) shouldBe Left((StatusCode.Forbidden, "account_disabled"))
    code(f.mine("off-tok")) shouldBe Left((StatusCode.Forbidden, "account_disabled"))
  }

  "removeMine" should "remove the caller's own credential, never another user's" in {
    val f = new Fixture
    val g = f.add("gina-tok", req("s3://globex-etl/")).toOption.get
    code(f.h.removeMine(ProfileS3RemoveRequest(g.id), Some("alice-tok")).unsafeRunSync()) shouldBe
      Left((StatusCode.NotFound, "not_found"))
    f.h
      .removeMine(ProfileS3RemoveRequest(g.id), Some("gina-tok"))
      .unsafeRunSync()
      .map(_.id) shouldBe
      Right(g.id)
    f.mine("gina-tok").toOption.get.credentials shouldBe Nil
  }

  "the admin view" should "list and remove a user's credentials for their own tenant's admin" in {
    val f      = new Fixture
    val c      = f.add("alice-tok", req()).toOption.get
    val listed =
      f.h.listForUser(UserS3ListRequest(f.alice), Some("admin-tok"))(f.scopeOf).unsafeRunSync()
    listed.map(_.credentials.map(_.id)) shouldBe Right(List(c.id))
    listed.toOption.get.asJson.noSpaces should not include Secret
    f.h
      .removeForUser(UserS3RemoveRequest(f.alice, c.id), Some("admin-tok"))(f.scopeOf)
      .unsafeRunSync()
      .map(_.id) shouldBe Right(c.id)
  }

  it should "refuse another tenant's admin, a session with no admin grant, and 404 an unknown user" in {
    val f = new Fixture
    val g = f.add("gina-tok", req("s3://globex-etl/")).toOption.get
    List("admin-tok", "alice-tok").foreach { t =>
      code(f.h.listForUser(UserS3ListRequest(f.gina), Some(t))(f.scopeOf).unsafeRunSync()) shouldBe
        Left((StatusCode.Forbidden, "tenant_forbidden"))
      code(
        f.h.removeForUser(UserS3RemoveRequest(f.gina, g.id), Some(t))(f.scopeOf).unsafeRunSync()
      ) shouldBe Left((StatusCode.Forbidden, "tenant_forbidden"))
    }
    f.sup.userS3.of(f.gina).map(_.id) shouldBe List(g.id)
    code(
      f.h.listForUser(UserS3ListRequest("u-nobody"), Some("root-tok"))(f.scopeOf).unsafeRunSync()
    ) shouldBe
      Left((StatusCode.NotFound, "not_found"))
    f.h
      .listForUser(UserS3ListRequest(f.gina), Some("root-tok"))(f.scopeOf)
      .unsafeRunSync()
      .isRight shouldBe
      true
  }

  "the add request" should "never print its secret or token" in {
    req().toString should not include Secret
    req().toString should not include Token
  }

  "an add and a remove" should "each write their audit row, naming the row but no key" in {
    val f = new Fixture
    val c = f.add("alice-tok", req()).toOption.get
    f.h.removeMine(ProfileS3RemoveRequest(c.id), Some("alice-tok")).unsafeRunSync().isRight shouldBe
      true
    val rows = f.auditRows.events.toList
    rows.map(_.action) shouldBe List(
      AuditActions.UserS3CredentialAdd,
      AuditActions.UserS3CredentialRemove
    )
    rows.foreach { r =>
      r.target shouldBe Some("s3://acme-etl/")
      r.detail shouldBe Map("user" -> f.alice, "id" -> c.id)
      r.detail.values.mkString should not include Secret
    }
  }
