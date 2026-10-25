package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.TenantSignInWrite.{Refusal, Result}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** One test per write rule of `tenant/setAuth` (spec 2026-10-24 section 4, "Write rules"). */
class TenantSignInWriteSpec extends AnyFlatSpec with Matchers:

  private val Issuer = "https://idp.example.com/realms/acme"
  private val Other  = "https://other.example.com"
  private val Ref    = "env:ACME_OIDC_SECRET"
  private val cfg    = Map("issuerUrl" -> Issuer, "clientId" -> "qod")

  /** Stores a client secret value under `Issuer`. */
  private val withValue =
    Tenant("acme", authProvider = "keycloak", authConfig = cfg, authClientSecret = Some("s3cr3t"))

  /** Stores the reference `Ref` (set by a superuser) under `Issuer`. */
  private val withRef =
    Tenant("acme", authProvider = "keycloak", authConfig = cfg + ("clientSecretRef" -> Ref))

  /** Holds a reference changeset 0048 parked, and no secret source. */
  private val parked = Tenant(
    "acme",
    authProvider = "keycloak",
    authConfig = cfg + (ParkedSecretRefs.ParkedKey -> "env:OLD")
  )

  private def write(
      current: Tenant,
      config: Map[String, String],
      secret: Option[String] = None,
      superuser: Boolean = false
  ): Either[Refusal, Result] =
    TenantSignInWrite(current, config, secret, superuser)

  "TenantSignInWrite" should
    "keep the stored value when clientSecret is omitted and the issuer is unchanged" in {
      write(withValue, cfg) shouldBe Right(Result(cfg, Some("s3cr3t")))
    }

  it should "compare issuers trimmed, so surrounding spaces name the same issuer" in:
    write(withValue, cfg.updated("issuerUrl", s"  $Issuer ")).map(_.authClientSecret) shouldBe
      Right(Some("s3cr3t"))

  it should "drop the stored value when the issuer changes and no secret is supplied" in:
    write(withValue, cfg.updated("issuerUrl", Other)).map(_.authClientSecret) shouldBe Right(None)

  it should "treat a removed or blank issuer as an issuer change" in:
    write(withValue, cfg - "issuerUrl").map(_.authClientSecret) shouldBe Right(None)
    write(withValue, cfg.updated("issuerUrl", "  ")).map(_.authClientSecret) shouldBe Right(None)

  it should "clear the stored value on an empty clientSecret" in:
    write(withValue, cfg, Some("")).map(_.authClientSecret) shouldBe Right(None)

  it should "replace the stored value with a supplied one, under a new issuer too" in:
    write(withValue, cfg, Some("n3w")).map(_.authClientSecret) shouldBe Right(Some("n3w"))
    write(withValue, cfg.updated("issuerUrl", Other), Some("n3w")).map(_.authClientSecret) shouldBe
      Right(Some("n3w"))

  it should "store a supplied value as given, without trimming it" in:
    write(Tenant("acme"), cfg, Some(" pad ")).map(_.authClientSecret) shouldBe Right(Some(" pad "))

  it should "refuse a whitespace-only clientSecret" in:
    write(withValue, cfg, Some("   ")) shouldBe Left(Refusal.InvalidClientSecret)
    write(withValue, cfg, Some("\t\n")) shouldBe Left(Refusal.InvalidClientSecret)

  it should "refuse a clientSecret key inside authConfig, for a superuser too" in:
    write(Tenant("acme"), cfg + ("clientSecret" -> "x")) shouldBe Left(Refusal.SecretInConfig)
    write(Tenant("acme"), cfg + ("clientSecret" -> "x"), superuser = true) shouldBe
      Left(Refusal.SecretInConfig)

  it should "refuse a reference added by a non-superuser, a value in the same write included" in:
    val added = cfg + ("clientSecretRef" -> "env:AWS_SECRET_ACCESS_KEY")
    write(withValue, added) shouldBe Left(Refusal.SuperuserRequired)
    write(withValue, added, Some("n3w")) shouldBe Left(Refusal.SuperuserRequired)

  it should "refuse a reference changed by a non-superuser" in:
    write(withRef, cfg + ("clientSecretRef" -> "env:OTHER")) shouldBe
      Left(Refusal.SuperuserRequired)

  it should "store a reference a superuser adds, and drop the stored value with it" in:
    write(withValue, cfg + ("clientSecretRef" -> Ref), superuser = true) shouldBe
      Right(Result(cfg + ("clientSecretRef" -> Ref), None))

  it should "store a reference a superuser changes, under a new issuer too" in:
    val moved = Map("issuerUrl" -> Other, "clientId" -> "qod", "clientSecretRef" -> "env:OTHER")
    write(withRef, moved, superuser = true) shouldBe Right(Result(moved, None))

  it should "keep a reference a non-superuser carries unchanged under the same issuer" in:
    val carried = cfg.updated("clientId", "qod2") + ("clientSecretRef" -> Ref)
    write(withRef, carried) shouldBe Right(Result(carried, None))

  it should "count a carried reference that differs only by surrounding spaces as unchanged" in:
    write(withRef, cfg + ("clientSecretRef" -> s" $Ref ")).isRight shouldBe true

  it should "keep a reference a superuser carries to a new issuer (re-pairing in one write)" in:
    val moved = Map("issuerUrl" -> Other, "clientId" -> "qod", "clientSecretRef" -> Ref)
    write(withRef, moved, superuser = true) shouldBe Right(Result(moved, None))

  it should "drop a reference a non-superuser carries unchanged to a new issuer" in:
    val moved = Map("issuerUrl" -> Other, "clientId" -> "qod", "clientSecretRef" -> Ref)
    write(withRef, moved) shouldBe Right(Result(moved - "clientSecretRef", None))

  it should "remove the stored reference when the request leaves the key out" in:
    write(withRef, cfg) shouldBe Right(Result(cfg, None))

  it should "remove any reference when a value is supplied" in:
    write(withRef, cfg + ("clientSecretRef" -> Ref), Some("n3w")) shouldBe
      Right(Result(cfg, Some("n3w")))

  it should "refuse a superuser write that supplies a value and a new reference" in:
    write(withValue, cfg + ("clientSecretRef" -> "env:NEW"), Some("n3w"), superuser = true) shouldBe
      Left(Refusal.SecretConflict)

  it should "drop the parked key once the result holds a client secret value" in:
    write(parked, cfg + (ParkedSecretRefs.ParkedKey -> "env:OLD"), Some("n3w")) shouldBe
      Right(Result(cfg, Some("n3w")))

  it should "drop the parked key once a superuser restores the reference" in:
    val restored = cfg + ("clientSecretRef" -> "env:OLD")
    write(parked, restored + (ParkedSecretRefs.ParkedKey -> "env:OLD"), superuser = true) shouldBe
      Right(Result(restored, None))

  it should "keep a parked key carried unchanged while the result holds no secret source" in:
    val roundTrip = cfg.updated("clientId", "qod2") + (ParkedSecretRefs.ParkedKey -> "env:OLD")
    write(parked, roundTrip) shouldBe Right(Result(roundTrip, None))

  it should "never keep a parked key the request adds, changes or leaves out" in:
    val forged = cfg + (ParkedSecretRefs.ParkedKey -> "env:AWS_SECRET_ACCESS_KEY")
    write(parked, forged).map(_.authConfig) shouldBe Right(cfg)
    write(Tenant("acme", authConfig = cfg), forged).map(_.authConfig) shouldBe Right(cfg)
    write(parked, cfg).map(_.authConfig) shouldBe Right(cfg)

  it should "answer each refusal with its wire code" in:
    Refusal.SuperuserRequired.code shouldBe "superuser_required"
    Refusal.InvalidClientSecret.code shouldBe "invalid_client_secret"
    Refusal.SecretConflict.code shouldBe "secret_conflict"
    Refusal.SecretInConfig.code shouldBe "invalid_auth_config"
    Refusal.SecretInConfig.message should include("clientSecret")

  "TenantSignInWrite.Result" should "redact the secret in toString" in:
    Result(cfg, Some("s3cr3t")).toString should not include "s3cr3t"

  "TenantSignInWrite.issuerOf" should "trim issuerUrl and read a blank or missing one as none" in:
    TenantSignInWrite.issuerOf(Map("issuerUrl" -> s"  $Issuer ")) shouldBe Some(Issuer)
    TenantSignInWrite.issuerOf(Map("issuerUrl" -> "   ")) shouldBe None
    TenantSignInWrite.issuerOf(Map("clientId" -> "qod")) shouldBe None
