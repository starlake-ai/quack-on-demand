package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.auth.PatPrincipal

/** The two credentials `/mcp` admits. Session JWTs and passwords are refused at the route, so every
  * tool sees exactly one of these.
  */
sealed trait McpPrincipal:
  def isAdmin: Boolean

  /** The raw bearer, for currying into the REST handlers' `apiKey` seam so their scope gates
    * resolve the same principal this route did. `None` for the static key: the handlers' own
    * static-key arm is not re-entered from here.
    */
  def rawToken: Option[String]

object McpPrincipal:

  /** The static `QOD_API_KEY`: superuser-equivalent, cross-tenant. */
  case object StaticKey extends McpPrincipal:
    def isAdmin: Boolean         = true
    def rawToken: Option[String] = None

  /** A personal access token, resolved to its owning user's live principal. The raw bearer rides
    * along for handler currying but is a plain constructor arg (not a case field), so it never
    * appears in `toString` or log output.
    */
  final class Pat(val p: PatPrincipal, raw: String) extends McpPrincipal:
    def isAdmin: Boolean          = p.isAdmin
    def rawToken: Option[String]  = Some(raw)
    override def toString: String = s"Pat(${p.user.username}, pat=${p.patId})"

  object Pat:
    def unapply(x: Pat): Some[PatPrincipal] = Some(x.p)
