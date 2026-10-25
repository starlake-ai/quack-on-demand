package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant

/** The executor identity a REST catalog handler runs a statement under. Regression anchor: a PAT
  * used to fall through the JWT-only lookup to the synthetic superuser. Each caller also carries
  * its realm (`superuserAdmissible`): true only for a superuser's session or PAT, and for the
  * static key.
  */
class RestCallerSpec extends AnyFlatSpec with Matchers:

  import IdentityFixtures.*

  private val resolver = RestCaller(Some(StaticKey), sessionOf, patOf)

  // A superuser (a tenant-less row) holding a session and two PATs: a plain one, and a `dropAdmin`
  // one whose admin scope collapses to NoAccess while its owning row stays tenant-less.
  private val RootSessionTok = "root-session"
  private val RootPatTok     = "qod_pat_root"
  private val RootDataPatTok = "qod_pat_root_data"
  private val rootRow        =
    RbacUser(id = "u-root", tenant = None, username = "root", kind = "admin")

  private val realmResolver = RestCaller(
    Some(StaticKey),
    t =>
      sessionOf(t).orElse(
        Option.when(t == RootSessionTok)(
          SessionTokenStore.Session(
            AuthenticatedProfile("root", "admin", Set.empty, Map.empty, "db", None),
            SessionScope.Superuser,
            Instant.now()
          )
        )
      ),
    t =>
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

  "RestCaller" should "run the static key as the unrestricted superuser" in {
    shape(resolver("conn", Some(StaticKey))) shouldBe
      Right(
        ("conn", CatalogPreviewHandlers.SuperuserIdentity, TokenRestriction.Unrestricted, None)
      )
  }

  it should "treat an absent credential as the static key (MCP static principal, guard-admitted)" in {
    shape(resolver("conn", None)) shouldBe
      Right(
        ("conn", CatalogPreviewHandlers.SuperuserIdentity, TokenRestriction.Unrestricted, None)
      )
  }

  it should "run a session JWT as its user, unrestricted" in {
    shape(resolver("conn", Some(SessionTok))) shouldBe
      Right(("conn", "alice", TokenRestriction.Unrestricted, None))
  }

  it should "run a PAT as its owner with the PAT's restriction and id" in {
    shape(resolver("conn", Some(PatTok))) shouldBe
      Right(("conn", "alice", PatRestriction, Some(PatId)))
  }

  it should "refuse an unknown, expired or revoked token with 401, never superuser" in
    List("qod_pat_unknown", "garbage", "").foreach { t =>
      resolver("conn", Some(t)).left.toOption.map(e => (e._1, e._2.error)) shouldBe
        Some((StatusCode.Unauthorized, "unauthorized"))
    }

  it should "match the static key exactly (no prefix, suffix or case variant)" in
    List(StaticKey + "x", StaticKey.dropRight(1), StaticKey.toUpperCase, " " + StaticKey)
      .foreach { t =>
        resolver("conn", Some(t)).isLeft shouldBe true
      }

  it should "never match an empty configured static key" in {
    val r = RestCaller(Some(""), sessionOf, patOf)
    r("conn", Some("")).isLeft shouldBe true
  }

  it should "refuse every token when only the static key is known" in {
    RestCaller.staticOnly("conn", Some(PatTok)).isLeft shouldBe true
    RestCaller.staticOnly("conn", Some(SessionTok)).isLeft shouldBe true
  }

  it should "flag only the static key (and the absent credential) as a system caller" in {
    resolver("conn", Some(StaticKey)).map(_.system) shouldBe Right(true)
    resolver("conn", None).map(_.system) shouldBe Right(true)
    resolver("conn", Some(SessionTok)).map(_.system) shouldBe Right(false)
    resolver("conn", Some(PatTok)).map(_.system) shouldBe Right(false)
  }

  it should "never make a session or PAT whose user is named 'superuser' a system or superuser-admissible caller" in {
    val r = RestCaller(Some(StaticKey), sentinelSessionOf, sentinelPatOf)
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
      .map(c => (c.identity, c.superuserAdmissible)) shouldBe Right(("root", true))
  }

  it should "take a PAT's superuserAdmissible from its owning row's realm, not from the token's admin scope" in {
    realmResolver("conn", Some(PatTok)).map(c => (c.identity, c.superuserAdmissible)) shouldBe
      Right(("alice", false))
    realmResolver("conn", Some(RootPatTok)).map(c => (c.identity, c.superuserAdmissible)) shouldBe
      Right(("root", true))
    realmResolver("conn", Some(RootDataPatTok))
      .map(c => (c.identity, c.superuserAdmissible, c.system)) shouldBe Right(("root", true, false))
  }

  it should "make the static key and the absent credential superuser-admissible system callers" in {
    resolver("conn", Some(StaticKey)).map(c => (c.system, c.superuserAdmissible)) shouldBe
      Right((true, true))
    resolver("conn", None).map(c => (c.system, c.superuserAdmissible)) shouldBe Right((true, true))
  }
