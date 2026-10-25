package ai.starlake.quack.edge.auth

/** Whether the user name an identity provider produced may name a `qodstate_user` row. One rule for
  * every plane: the management login (`AuthHandlers.mintSessionFor`) and the data-plane handshake
  * every front door shares (`EdgeHandshake`: FlightSQL and the native Quack front door).
  *
  * The IdP authenticators (`OidcBearerAuthenticator`, `JwtBearerAuthenticator`,
  * `ResourceOwnerPasswordAuthenticator`) name the user by the token's `preferred_username`, else
  * its `email` claim, else its subject, and carry those claims in the profile. A name that came
  * from the email claim (the claims carry no `preferred_username` and the `email` claim equals the
  * user name) is an address its account holder may have typed, which could be the name of a
  * superuser's row (the seeded `admin@localhost.local`): it counts only when the IdP marks the
  * address verified. Any other name (a `preferred_username`, a subject) counts as it is. The
  * database authenticator's profile carries no `email` claim, so the rule never refuses it.
  */
object IdentityNaming:

  /** Why a data-plane handshake refuses a name the rule does not count. */
  val UnverifiedEmailRefusal: String =
    "the identity provider did not verify the email address that names this account; " +
      "configure it to send preferred_username or to verify email addresses"

  /** Whether the IdP marks the token's email address verified: `email_verified` is true (the
    * flattened claims hold the string "true" for a JSON boolean or a string alike). Anything else,
    * `True` included, is not verified, so a doubtful value fails closed.
    */
  def emailVerified(profile: AuthenticatedProfile): Boolean =
    profile.claims.get("email_verified").contains("true")

  /** The email claim of a token, only when the IdP marks it verified. An address the IdP does not
    * vouch for is whatever its account holder typed, so it must not name a `qodstate_user` row: an
    * unverified address equal to the superuser's user name would bind it.
    */
  def verifiedEmail(profile: AuthenticatedProfile): Option[String] =
    profile.claims.get("email").filter(_ => emailVerified(profile))

  /** Whether the profile's user name may name a `qodstate_user` row: false only for a name that
    * came from the email claim of a token whose IdP does not mark the address verified.
    */
  def userNameMayBindRow(profile: AuthenticatedProfile): Boolean =
    val nameIsEmail = !profile.claims.contains("preferred_username") &&
      profile.claims.get("email").contains(profile.username)
    !nameIsEmail || emailVerified(profile)
