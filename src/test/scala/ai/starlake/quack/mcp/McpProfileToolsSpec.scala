package ai.starlake.quack.mcp

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{ProfileS3AddRequest, RestCaller, UserS3CredentialHandlers}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McpProfileToolsSpec extends AnyFlatSpec with Matchers:

  private val Secret = "mcp-user-sekr3t"

  private final class Fixture:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val alice = store.upsertUserWithHash(Some("acme"), "alice", "x", "user")
    val bob   = store.upsertUserWithHash(Some("acme"), "bob", "x", "user")

    private def pat(id: String, name: String, token: String) =
      token -> PatPrincipal(
        RbacUser(id, Some("acme"), name, "user"),
        s"pat-$name",
        SessionScope(superuser = false, manageableTenants = Set.empty),
        isAdmin = false,
        TokenRestriction.Unrestricted
      )

    private val pats = Map(pat(alice, "alice", "qod_pat_alice"), pat(bob, "bob", "qod_pat_bob"))

    val handlers = new UserS3CredentialHandlers(
      sup,
      RestCaller(sessionOf = _ => None, patOf = pats.get),
      Nil,
      _ => Right(None)
    )
    val tools = new McpProfileTools(handlers)

    def principal(token: String): McpPrincipal = new McpPrincipal.Pat(pats(token), token)

    def call(name: String, token: String, args: (String, Json)*): Either[String, Json] =
      val tool = tools.tools.find(_.name == name).getOrElse(fail(s"tool $name not defined"))
      tool.run(principal(token), JsonObject.fromIterable(args)).unsafeRunSync()

    def addFor(token: String, scope: String): String =
      handlers
        .addMine(ProfileS3AddRequest(scope, "AKIAEXAMPLE", Secret), Some(token))
        .unsafeRunSync()
        .toOption
        .get
        .id

  "the profile tools" should "be open to any token, and offer no add" in {
    val f = new Fixture
    f.tools.tools.map(_.name).toSet shouldBe Set(
      "list_my_s3_credentials",
      "remove_my_s3_credential"
    )
    f.tools.tools.forall(!_.adminOnly) shouldBe true
  }

  "list_my_s3_credentials" should "list the caller's own credentials, never their secret" in {
    val f = new Fixture
    f.addFor("qod_pat_alice", "s3://acme-etl/")
    f.addFor("qod_pat_bob", "s3://bob-etl/")
    val out = f.call("list_my_s3_credentials", "qod_pat_alice").toOption.get.noSpaces
    out should include("s3://acme-etl/")
    out should not include "s3://bob-etl/"
    out should not include Secret
  }

  "remove_my_s3_credential" should "remove the caller's own credential and refuse another's" in {
    val f   = new Fixture
    val bob = f.addFor("qod_pat_bob", "s3://bob-etl/")
    f.call("remove_my_s3_credential", "qod_pat_alice", "id" -> Json.fromString(bob)).isLeft shouldBe
      true
    f.call("remove_my_s3_credential", "qod_pat_bob", "id" -> Json.fromString(bob)).isRight shouldBe
      true
    f.call("remove_my_s3_credential", "qod_pat_bob").isLeft shouldBe true
  }
