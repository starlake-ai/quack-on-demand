package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import sttp.model.StatusCode

/** Gate for operations on shared infrastructure (node quarantine / restart, tenant creation, fleet
  * servers, manifests, the server config): admits exactly a superuser scope. Anyone else, a caller
  * with no credential or an unresolvable one ([[SessionScope.NoAccess]]) included, gets
  * `403 superuser_required`.
  */
object SuperuserCheck:

  /** The rule, on an already-resolved scope. The shared create paths call it directly with their
    * caller's scope (`SessionScope.Superuser` for boot provisioning); [[reject]] resolves a REST or
    * MCP credential first.
    */
  def forScope(
      scope: SessionScope,
      message: String = "this operation requires a superuser session"
  ): Option[(StatusCode, ErrorResponse)] =
    if scope.superuser then None
    else Some(StatusCode.Forbidden -> ErrorResponse("superuser_required", message))

  /** [[forScope]] on the caller's credential, resolved by the total `scopeOf`. */
  def reject(
      apiKey: Option[String],
      message: String = "this operation requires a superuser session"
  )(
      scopeOf: SessionScope.Resolver
  ): Option[(StatusCode, ErrorResponse)] =
    forScope(scopeOf(apiKey), message)
