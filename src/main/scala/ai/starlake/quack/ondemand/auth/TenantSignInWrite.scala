package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.model.{FederatedSecret, Tenant}

/** The write rules of `tenant/setAuth` (the MCP `set_tenant_auth` tool calls the same handler): a
  * pure function from the stored tenant, the request and whether the caller is a superuser to the
  * sign-in state to store, so every rule is tested on its own. Two rules carry it:
  *
  *   - A tenant's client secret is only ever sent to the issuer it was saved with ([[issuerOf]]). A
  *     stored value survives a write only when the issuer stays the same, and so does a stored
  *     `clientSecretRef` a non-superuser carries unchanged. Under a new issuer a secret exists only
  *     when the same write supplies one: a value, or a reference from a superuser, who may pair a
  *     reference with any issuer in one write (added, changed or carried).
  *   - Only a superuser may point a tenant at a secret of the manager's own environment: a
  *     `clientSecretRef` that a write adds or changes needs a superuser, and anyone else's write is
  *     refused with nothing stored.
  *
  * One secret source per tenant: a value supplied in the write removes any reference, a reference
  * added or changed removes the stored value, and a write doing both is refused. A secret value
  * only arrives through the write-only `clientSecret` field, never inside `authConfig`
  * (`tenant/create` refuses a [[SecretKey]] key the same way).
  *
  * The key changeset 0048 parked old references under ([[ParkedSecretRefs.ParkedKey]]) goes with
  * the first result that holds a secret source (a value or a reference), so restoring through any
  * client clears it and its boot report. Until then it is kept only as stored: carried unchanged by
  * a request that keeps the issuer (a form round trip). One a request adds or changes is dropped,
  * and so is one carried to another issuer, for a superuser too: only the changeset writes it, and
  * its boot report tells a superuser which reference to restore for the issuer it was parked under,
  * so it must not outlive a tenant admin pointing the tenant at an identity provider of their own.
  * [[settleParkedKey]] is that rule in one place, shared with the manifest import.
  */
object TenantSignInWrite:

  val IssuerKey: String    = "issuerUrl"
  val SecretRefKey: String = "clientSecretRef"
  val SecretKey: String    = "clientSecret"

  /** How a client spells the write-only secret field. Every refusal that names the field names all
    * three, because the one handler answers REST, MCP and CLI callers alike.
    */
  private val SecretField: String =
    "clientSecret over REST, client_secret over MCP, --client-secret-stdin or " +
      "--client-secret-prompt in the CLI"

  enum Refusal(val code: String, val message: String):
    case SuperuserRequired
        extends Refusal(
          "superuser_required",
          "only a superuser may add or change clientSecretRef, which names a secret of the " +
            s"manager's environment; send the tenant's own secret instead ($SecretField)"
        )
    case InvalidClientSecret
        extends Refusal(
          "invalid_client_secret",
          "the client secret is blank: send the secret, or an empty string to remove the " +
            s"stored one ($SecretField)"
        )
    case SecretConflict
        extends Refusal(
          "secret_conflict",
          "a tenant has one client secret source: send the client secret or a new " +
            s"clientSecretRef, not both ($SecretField)"
        )
    case SecretInConfig
        extends Refusal(
          "invalid_auth_config",
          "authConfig may not carry clientSecret: a client secret only goes through the " +
            s"write-only field of tenant/setAuth ($SecretField)"
        )

  /** The complete sign-in state to store. `toString` redacts the secret. */
  final case class Result(authConfig: Map[String, String], authClientSecret: Option[String]):
    override def toString: String =
      s"Result(authConfig=$authConfig,authClientSecret=${authClientSecret
          .map(_ => FederatedSecret.RedactedMarker)})"

  /** The issuer a client secret is bound to: `issuerUrl` trimmed, empty meaning none. The one
    * issuer comparison: every rule that binds a secret to its issuer (this write, the manifest
    * import) calls it.
    */
  def issuerOf(authConfig: Map[String, String]): Option[String] =
    authConfig.get(IssuerKey).map(_.trim).filter(_.nonEmpty)

  /** What counts as a secret reference in an `authConfig`, read as the resolvers read it: the
    * trimmed [[SecretRefKey]], a blank one being none. The one definition: this write and the
    * manifest import both ask it.
    */
  def refOf(cfg: Map[String, String]): Option[String] =
    cfg.get(SecretRefKey).map(_.trim).filter(_.nonEmpty)

  /** `next`, the `authConfig` about to be stored, with the key changeset 0048 parked
    * ([[ParkedSecretRefs.ParkedKey]]) settled against `current`, the one stored. The key stays only
    * while all three hold: the result holds no secret source (`secret` is none and `next` carries
    * no `refOf` reference), the issuer is unchanged (`issuerOf`), and `next` carries the key
    * exactly as `current` stores it. Otherwise it is dropped: a secret source ends the boot report
    * the key feeds, a moved or removed issuer makes it a hint for an identity provider the tenant
    * no longer has, and a key a request adds, changes or leaves out was not written by the
    * changeset. No exception for a superuser: the key is a hint for the boot line, not a secret
    * source.
    *
    * Pure, and the one place the rule lives: `tenant/setAuth` ([[apply]]) and the manifest import
    * both store the map it returns.
    */
  def settleParkedKey(
      current: Map[String, String],
      next: Map[String, String],
      secret: Option[String]
  ): Map[String, String] =
    val parkedKey   = ParkedSecretRefs.ParkedKey
    val holdsSource = secret.isDefined || refOf(next).isDefined
    val keep        = !holdsSource && issuerOf(current) == issuerOf(next) &&
      next.get(parkedKey) == current.get(parkedKey)
    if keep then next else next - parkedKey

  /** `clientSecret`: None keeps the stored value (same issuer only), `Some("")` clears it, any
    * other value replaces it. `authConfig` replaces the stored map whole. Refusals, in order: a
    * `clientSecret` key in `authConfig`, a whitespace-only value, a reference added or changed by a
    * non-superuser, a value and a new reference in one write.
    */
  def apply(
      current: Tenant,
      authConfig: Map[String, String],
      clientSecret: Option[String],
      callerIsSuperuser: Boolean
  ): Either[Refusal, Result] =
    val sameIssuer   = issuerOf(current.authConfig) == issuerOf(authConfig)
    val requestedRef = refOf(authConfig)
    val refChanged   = requestedRef.isDefined && requestedRef != refOf(current.authConfig)
    val valueGiven   = clientSecret.exists(_.nonEmpty)
    if authConfig.contains(SecretKey) then Left(Refusal.SecretInConfig)
    else if clientSecret.exists(s => s.nonEmpty && s.trim.isEmpty) then
      Left(Refusal.InvalidClientSecret)
    else if refChanged && !callerIsSuperuser then Left(Refusal.SuperuserRequired)
    else if refChanged && valueGiven then Left(Refusal.SecretConflict)
    else
      // A reference the request carries stays unless a value replaces it: a superuser's with any
      // issuer (added, changed or carried), anyone else's (unchanged, or refused above) only under
      // the same issuer.
      val keepRef = requestedRef.isDefined && !valueGiven && (callerIsSuperuser || sameIssuer)
      val secret  = clientSecret match
        case Some(s) => Some(s).filter(_.nonEmpty)
        case None    => if sameIssuer && !refChanged then current.authClientSecret else None
      // The parked key follows settleParkedKey's rule against the map that is about to be stored.
      val withRef = if keepRef then authConfig else authConfig - SecretRefKey
      Right(Result(settleParkedKey(current.authConfig, withRef, secret), secret))
