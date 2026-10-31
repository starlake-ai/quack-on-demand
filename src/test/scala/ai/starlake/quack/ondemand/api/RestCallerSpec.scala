package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** The executor identity a REST catalog handler runs a statement under. Regression anchors: a PAT
  * used to fall through the JWT-only lookup to the synthetic superuser, and an absent credential
  * used to run as the system caller. Each caller also carries its realm (`superuserAdmissible`):
  * true only for a superuser's session or PAT.
  */
class RestCallerSpec extends AnyFlatSpec with Matchers:

  import IdentityFixtures.*

  private val resolver = RestCaller(sessionOf = sessionOf, patOf = patOf)

  // A superuser (a tenant-less row) holding a session and two PATs: a plain one, and a `dropAdmin`
  // one whose admin scope collapses to NoAccess while its owning row stays tenant-less.
  private val RootPatTok     = "qod_pat_root"
  private val RootDataPatTok = "qod_pat_root_data"
  private val rootRow        =
    RbacUser(id = "u-root", tenant = None, username = RootUsername, kind = "admin")

  private val realmResolver = RestCaller(
    sessionOf = t => sessionOf(t).orElse(rootSessionOf(t)),
    patOf = t =>
      patOf(t)
        .orElse(
          Option.when(t == RootPatTok)(
            PatPrincipal(
              user = rootRow,
              patId = "pat-root",
              scope = SessionScope.Superuser,
              isAdmin = true,
              restriction = TokenRestriction.Unrestricted
            )
          )
        )
        .orElse(
          Option.when(t == RootDataPatTok)(
            PatPrincipal(
              user = rootRow,
              patId = "pat-root-data",
              scope = SessionScope.NoAccess,
              isAdmin = false,
              restriction = TokenRestriction.Unrestricted.copy(dropAdmin = true)
            )
          )
        )
  )

  private def shape(r: Either[(StatusCode, ErrorResponse), ExecCaller]) =
    r.map(c => (c.connectionId, c.identity, c.restriction, c.patId))

  private def unauthorized(r: Either[(StatusCode, ErrorResponse), ExecCaller]) =
    r.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))

  "RestCaller" should "refuse an absent credential with 401, never a system caller" in {
    unauthorized(resolver("conn", None))
    resolver.resolve(None).isLeft shouldBe true
  }

  it should "run a session JWT as its user, unrestricted" in {
    shape(resolver("conn", Some(SessionTok))) shouldBe
      Right(("conn", "alice", TokenRestriction.Unrestricted, None))
  }

  it should "run a superuser session as its user, not as a system caller" in {
    val r = RestCaller(sessionOf = rootSessionOf)
    r("conn", Some(RootSessionTok))
      .map(c => (c.identity, c.restriction, c.system, c.superuserAdmissible)) shouldBe
      Right((RootUsername, TokenRestriction.Unrestricted, false, true))
  }

  it should "run a PAT as its owner with the PAT's restriction and id" in {
    shape(resolver("conn", Some(PatTok))) shouldBe
      Right(("conn", "alice", PatRestriction, Some(PatId)))
  }

  it should "refuse an unknown, expired or revoked token with 401" in
    List("qod_pat_unknown", "garbage", "", "static-key-1").foreach { t =>
      unauthorized(resolver("conn", Some(t)))
    }

  it should "refuse every credential when no lookup is wired" in
    List(None, Some(PatTok), Some(SessionTok)).foreach { k =>
      unauthorized(RestCaller()("conn", k))
    }

  it should "never flag a session or PAT as a system caller" in {
    resolver("conn", Some(SessionTok)).map(_.system) shouldBe Right(false)
    resolver("conn", Some(PatTok)).map(_.system) shouldBe Right(false)
  }

  it should "never make a session or PAT whose user is named 'superuser' a system or superuser-admissible caller" in {
    val r = RestCaller(sessionOf = sentinelSessionOf, patOf = sentinelPatOf)
    r("conn", Some(SentinelSessionTok))
      .map(c => (c.identity, c.system, c.superuserAdmissible)) shouldBe
      Right(("superuser", false, false))
    r("conn", Some(SentinelPatTok))
      .map(c => (c.identity, c.system, c.patId, c.superuserAdmissible)) shouldBe
      Right(("superuser", false, Some("pat-sentinel"), false))
  }

  it should "take a session's superuserAdmissible from its scope: true only for a superuser session" in {
    realmResolver("conn", Some(SessionTok)).map(c => (c.identity, c.superuserAdmissible)) shouldBe
      Right(("alice", false))
    realmResolver("conn", Some(RootSessionTok))
      .map(c => (c.identity, c.superuserAdmissible)) shouldBe Right((RootUsername, true))
  }

  it should "take a PAT's superuserAdmissible from its owning row's realm, not from the token's admin scope" in {
    realmResolver("conn", Some(PatTok)).map(c => (c.identity, c.superuserAdmissible)) shouldBe
      Right(("alice", false))
    realmResolver("conn", Some(RootPatTok)).map(c => (c.identity, c.superuserAdmissible)) shouldBe
      Right((RootUsername, true))
    realmResolver("conn", Some(RootDataPatTok))
      .map(c => (c.identity, c.superuserAdmissible, c.system)) shouldBe
      Right((RootUsername, true, false))
  }
