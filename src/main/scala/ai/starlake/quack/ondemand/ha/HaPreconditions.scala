package ai.starlake.quack.ondemand.ha

/** Config-load-time gates for HA mode. HA requires the Kubernetes backend (the local backend's port
  * allocator and child processes are per-JVM) and a non-default session JWT secret (sessions must
  * verify on every replica).
  */
object HaPreconditions:

  def validate(
      haEnabled: Boolean,
      runtimeType: String,
      sessionJwtSecret: String,
      devSecret: String
  ): Either[String, Unit] =
    if !haEnabled then Right(())
    else if runtimeType.toLowerCase != "kubernetes" && runtimeType.toLowerCase != "k8s" then
      Left(
        s"ha.enabled=true requires runtimeType=kubernetes, got '$runtimeType': the local " +
          "backend cannot run multi-manager (in-JVM port allocator, child processes)"
      )
    else if sessionJwtSecret == devSecret then
      Left(
        "ha.enabled=true requires a non-default QOD_SESSION_JWT_SECRET: replicas must " +
          "share a private signing key"
      )
    else Right(())
