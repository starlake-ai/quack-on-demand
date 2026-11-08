package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.security.AuditReport
import cats.effect.IO
import sttp.model.StatusCode

/** Superuser gate, then the audit. Nothing runs for anyone else. */
final class SecurityAuditHandlers(audit: IO[AuditReport]):
  def run(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Either[(StatusCode, ErrorResponse), SecurityAuditResponse]] =
    SuperuserCheck.forScope(scopeOf(apiKey), "the security audit is restricted to superusers") match
      case Some(refusal) => IO.pure(Left(refusal))
      case None          => audit.map(r => Right(SecurityAuditResponse.of(r)))
