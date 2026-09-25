package ai.starlake.quack.ondemand.fleet

enum ServerLiveness:
  case Reachable
  case Unreachable(silentSeconds: Long) // silent past heartbeatTimeoutSec, inside the grace
  case Dead                             // silent past reassignAfterSec

object FleetLiveness:
  /** Pure classification of one server's silence. `silentSeconds` is computed by the store from the
    * database clock so every HA replica gets the same answer. `Unreachable` carries the silence so
    * listings can show "since"; callers subtract it from the DB-derived lastHeartbeatAt if they
    * need an instant.
    */
  def classify(
      silentSeconds: Long,
      heartbeatTimeoutSec: Int,
      reassignAfterSec: Int
  ): ServerLiveness =
    if silentSeconds <= heartbeatTimeoutSec then ServerLiveness.Reachable
    else if reassignAfterSec == -1 then ServerLiveness.Unreachable(silentSeconds)
    else if silentSeconds > reassignAfterSec then ServerLiveness.Dead
    else ServerLiveness.Unreachable(silentSeconds)
