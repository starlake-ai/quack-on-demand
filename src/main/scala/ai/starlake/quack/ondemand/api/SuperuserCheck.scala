package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import sttp.model.StatusCode

/** Gate for operations on shared infrastructure (node quarantine / restart). Same admission rules
  * as TenantScopeCheck: no key and static-key callers are admitted (the perimeter is apiKeyGuard's
  * job); a resolved session is admitted only when it is a superuser session.
  */
object SuperuserCheck:
  def reject(
      apiKey: Option[String],
      message: String = "this operation requires a superuser session"
  )(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    apiKey.flatMap(scopeOf) match
      case Some(s) if !s.superuser =>
        Some(StatusCode.Forbidden -> ErrorResponse("superuser_required", message))
      case _ => None
