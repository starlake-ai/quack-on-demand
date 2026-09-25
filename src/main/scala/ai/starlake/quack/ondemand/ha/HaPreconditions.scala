package ai.starlake.quack.ondemand.ha

import java.util.Locale

/** Config-load-time gates for HA mode. HA requires a multi-manager-capable backend (Kubernetes or
  * fleet; the local backend's port allocator and child processes are per-JVM), an explicit session
  * JWT secret (sessions must verify on every replica, so the boot-generated per-replica fallback is
  * refused), and an external Postgres (a single embedded server cannot back N replicas).
  */
object HaPreconditions:

  private val multiManagerRuntimes = Set("kubernetes", "k8s", "fleet")

  def validate(
      haEnabled: Boolean,
      runtimeType: String,
      sessionJwtSecret: String,
      embeddedPostgres: Boolean = false
  ): Either[String, Unit] =
    if !haEnabled then Right(())
    else if embeddedPostgres then
      Left(
        "ha.enabled=true is incompatible with QOD_PG_EMBEDDED=true: a single embedded Postgres " +
          "process cannot be the shared control plane for multiple replicas. Point the replicas " +
          "at an external Postgres instead"
      )
    else if !HaPreconditions.multiManagerRuntimes.contains(runtimeType.toLowerCase(Locale.ROOT))
    then
      Left(
        s"ha.enabled=true requires runtimeType=kubernetes or fleet, got '$runtimeType': " +
          "the local backend cannot run multi-manager (in-JVM port allocator, child processes)"
      )
    else if sessionJwtSecret.trim.isEmpty then
      Left(
        "ha.enabled=true requires an explicit QOD_SESSION_JWT_SECRET: replicas must share a " +
          "private signing key, and the per-replica secret generated at boot cannot verify " +
          "sessions minted by other replicas"
      )
    else Right(())
