package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.auth.{PatPrincipal, TokenRestriction}

/** The one credential `/mcp` admits: a personal access token. Session JWTs, passwords and any other
  * bearer are refused at the route, so every tool sees a PAT principal.
  */
sealed trait McpPrincipal:
  def isAdmin: Boolean

  /** The raw bearer, for currying into the REST handlers' `apiKey` seam so their scope gates
    * resolve the same principal this route did. Always `Some`: the seam is an `Option` because a
    * REST request may arrive without a credential.
    */
  def rawToken: Option[String]

  /** The token's scope ceiling: the PAT's own `TokenRestriction`, already narrowed relative to its
    * owner's grants at mint time (`TokenRestriction.narrow`), so nothing further to attenuate here.
    */
  def restriction: TokenRestriction

object McpPrincipal:

  /** A personal access token, resolved to its owning user's live principal. The raw bearer rides
    * along for handler currying but is a plain constructor arg (not a case field), so it never
    * appears in `toString` or log output.
    */
  final class Pat(val p: PatPrincipal, raw: String) extends McpPrincipal:
    def isAdmin: Boolean              = p.isAdmin
    def rawToken: Option[String]      = Some(raw)
    def restriction: TokenRestriction = p.restriction
    override def toString: String     = s"Pat(${p.user.username}, pat=${p.patId})"

  object Pat:
    def unapply(x: Pat): Some[PatPrincipal] = Some(x.p)
