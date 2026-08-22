package ai.starlake.quack.ondemand.ha

/** Config-load-time gates for HA mode. HA requires the Kubernetes backend (the local backend's port
  * allocator and child processes are per-JVM) and an explicit session JWT secret (sessions must
  * verify on every replica, so the boot-generated per-replica fallback is refused).
  */
object HaPreconditions:

  def validate(
      haEnabled: Boolean,
      runtimeType: String,
      sessionJwtSecret: String
  ): Either[String, Unit] =
    if !haEnabled then Right(())
    else if runtimeType.toLowerCase != "kubernetes" && runtimeType.toLowerCase != "k8s" then
      Left(
        s"ha.enabled=true requires runtimeType=kubernetes, got '$runtimeType': the local " +
          "backend cannot run multi-manager (in-JVM port allocator, child processes)"
      )
    else if sessionJwtSecret.trim.isEmpty then
      Left(
        "ha.enabled=true requires an explicit QOD_SESSION_JWT_SECRET: replicas must share a " +
          "private signing key, and the per-replica secret generated at boot cannot verify " +
          "sessions minted by other replicas"
      )
    else Right(())
