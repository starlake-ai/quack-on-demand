package ai.starlake.quack.model

/** Wire encoding for the per-pool node-lockdown override: `Pool.lockdown: Option[Boolean]` as the
  * string tri-state `"inherit"` (`None`, follows the manager-global flag) | `"on"` (`Some(true)`) |
  * `"off"` (`Some(false)`). Used by the REST DTOs (create / setLockdown), the config manifest
  * export/import, and `PoolHandlers`' create-time superuser gate.
  */
object LockdownTriState:
  def parse(s: String): Either[String, Option[Boolean]] = s match
    case "inherit" => Right(None)
    case "on"      => Right(Some(true))
    case "off"     => Right(Some(false))
    case other     => Left(s"invalid lockdown value: '$other' (expected inherit | on | off)")

  def render(v: Option[Boolean]): String = v match
    case None        => "inherit"
    case Some(true)  => "on"
    case Some(false) => "off"
