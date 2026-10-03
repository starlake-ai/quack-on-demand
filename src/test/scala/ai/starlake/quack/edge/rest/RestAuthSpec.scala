package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PAT bearer intake, tenant binding and the tools axis of the REST data edge, without the handler
  * around them.
  */
class RestAuthSpec extends AnyFlatSpec with Matchers:

  private val Token      = "qod_pat_alice"
  private val SuperToken = "qod_pat_root"

  private def principal(
      tenant: Option[String],
      restriction: TokenRestriction = TokenRestriction.Unrestricted
  ) =
    PatPrincipal(
      user = RbacUser("u1", tenant, "alice", if tenant.isEmpty then "admin" else "user"),
      patId = "pat-1",
      scope = SessionScope(superuser = tenant.isEmpty, manageableTenants = Set.empty),
      isAdmin = tenant.isEmpty,
      restriction = restriction
    )

  private val lookups = scala.collection.mutable.ListBuffer.empty[String]

  private val resolve: String => Option[PatPrincipal] = t =>
    lookups += t
    if t == Token then Some(principal(Some("acme")))
    else if t == SuperToken then Some(principal(None))
    else if t.startsWith("qod_pat_long") then Some(principal(Some("acme")))
    else None

  private def auth(headers: String*) = RestAuth.authenticate(headers.toList, resolve)

  "authenticate" should "admit a live PAT bearer, whatever the scheme's case" in {
    auth(s"Bearer $Token").map(_.patId) shouldBe Right("pat-1")
    auth(s"bearer $Token").isRight shouldBe true
    auth(s"BEARER $Token").isRight shouldBe true
  }

  it should "answer one 401 for every other credential shape" in {
    val refused = List(
      Nil,
      List(s"Bearer $Token", s"Bearer $Token"),
      List("Basic YWxpY2U6c2VjcmV0"),
      List("Bearer "),
      List(Token),
      List("Bearer eyJhbGciOiJIUzI1NiJ9.e30.x"),
      List("Bearer qod_pat_unknown"),
      List(s"Bearer $SuperToken")
    )
    refused.foreach(h => RestAuth.authenticate(h, resolve) shouldBe Left(RestError.Unauthorized))
  }

  it should "refuse a superuser PAT with a body byte-identical to a garbage token's" in {
    auth(s"Bearer $SuperToken").left.map(_.toResponse) shouldBe
      auth("Bearer garbage").left.map(_.toResponse)
  }

  it should "never look up a token that is not a PAT" in {
    lookups.clear()
    auth("Bearer eyJhbGciOiJIUzI1NiJ9.e30.x")
    auth("Basic qod_pat_alice")
    lookups shouldBe empty
  }

  it should "accept a token of 8 KiB and refuse one byte more before any lookup" in {
    val at = "qod_pat_long" + "x" * (RestAuth.MaxTokenBytes - "qod_pat_long".length)
    at.length shouldBe RestAuth.MaxTokenBytes
    auth(s"Bearer $at").isRight shouldBe true
    lookups.clear()
    auth(s"Bearer ${at}x") shouldBe Left(RestError.Unauthorized)
    lookups shouldBe empty
  }

  "admit" should "bind the path tenant to the token's own tenant" in {
    val p = principal(Some("acme"))
    RestAuth.admit(p, "acme") shouldBe Right(())
    val other   = RestAuth.admit(p, "globex")
    val unknown = RestAuth.admit(p, "no_such_tenant")
    other shouldBe unknown
    other.left.map(_.toResponse._1.code) shouldBe Left(403)
    other.left.map(_.message) shouldBe Left("your token is scoped to tenant 'acme'")
  }

  it should "admit an unrestricted tools axis and one listing rest, and refuse any other list" in {
    def withTools(t: Option[Set[String]]) =
      principal(Some("acme"), TokenRestriction.Unrestricted.copy(tools = t))
    RestAuth.admit(withTools(None), "acme") shouldBe Right(())
    RestAuth.admit(withTools(Some(Set("rest", "run_sql"))), "acme") shouldBe Right(())
    RestAuth.admit(withTools(Some(Set("run_sql"))), "acme").left.map(_.code) shouldBe
      Left("forbidden")
    RestAuth.admit(withTools(Some(Set.empty)), "acme").left.map(_.code) shouldBe Left("forbidden")
  }

  "callerFor" should "build the caller McpDataTools builds for a PAT, tagged rest-data" in {
    val r = TokenRestriction.Unrestricted.copy(maxRows = Some(10), branchOnly = true)
    val c = RestAuth.callerFor(principal(Some("acme"), r))
    c.connectionId shouldBe "rest-pat-1"
    c.identity shouldBe "alice"
    c.patId shouldBe Some("pat-1")
    c.source shouldBe "rest-data"
    c.edge shouldBe "rest-data"
    c.system shouldBe false
    c.restriction shouldBe r
    c.preferredNode shouldBe None
  }
