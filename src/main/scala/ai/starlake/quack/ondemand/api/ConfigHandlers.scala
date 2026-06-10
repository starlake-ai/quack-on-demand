package ai.starlake.quack.ondemand.api

import cats.effect.IO
import com.typesafe.config.{Config, ConfigException}
import sttp.model.StatusCode

/** Resolves the static `ConfigRegistry` against the live Lightbend Config (post-env-var
  * substitution) and masks sensitive values.
  *
  * The handler is read-only; mutating a value still requires setting the listed env var and
  * restarting the manager. Access is gated to superusers -- a UI session whose profile carries a
  * tenant scope is rejected here even though it passed `apiKeyGuard`. Static `QOD_API_KEY` callers
  * (no session) are admitted; they're already trusted operators.
  */
final class ConfigHandlers(config: Config, entries: List[ConfigEntry]):

  def list(apiKey: Option[String])(
      resolveTenant: String => Option[Option[String]]
  ): IO[Either[(StatusCode, ErrorResponse), ConfigListResponse]] = IO.delay {
    apiKey.flatMap(resolveTenant) match
      case Some(Some(_)) =>
        Left(
          StatusCode.Forbidden ->
            ErrorResponse(
              "superuser_required",
              "the server config endpoint is restricted to superusers"
            )
        )
      case _ =>
        val views = entries.map { e =>
          val (isSet, raw) =
            try
              val s = config.getValue(e.path).unwrapped().toString
              // Treat empty string as "unset" so OIDC/JWT placeholder defaults
              // (`secretKey = ""`) render as "(unset)" rather than blank.
              (s.nonEmpty, s)
            catch
              case _: ConfigException.Missing => (false, "")
              case _: ConfigException.Null    => (false, "")

          val value =
            if e.sensitive then if isSet then "(set)" else "(unset)"
            else if !isSet then "(unset)"
            else raw

          ConfigEntryView(
            path = e.path,
            envVar = e.envVar,
            description = e.description,
            value = value,
            sensitive = e.sensitive,
            isSet = isSet
          )
        }
        Right(ConfigListResponse(views))
  }
