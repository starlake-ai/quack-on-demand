package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.RuntimeType

/** Config-load-time gates for HA mode. HA requires a multi-manager-capable backend (Kubernetes or
  * fleet; the local backend's port allocator and child processes are per-JVM) and an external
  * Postgres (a single embedded server cannot back N replicas). The session JWT secret needs no
  * gate: with none configured, every replica reads the one the control plane stores.
  */
object HaPreconditions:

  def validate(
      haEnabled: Boolean,
      runtimeType: String,
      embeddedPostgres: Boolean = false
  ): Either[String, Unit] =
    if !haEnabled then Right(())
    else if embeddedPostgres then
      Left(
        "ha.enabled=true is incompatible with QOD_PG_EMBEDDED=true: a single embedded Postgres " +
          "process cannot be the shared control plane for multiple replicas. Point the replicas " +
          "at an external Postgres instead"
      )
    else if !RuntimeType.isMultiManager(runtimeType) then
      Left(
        s"ha.enabled=true requires runtimeType=kubernetes or fleet, got '$runtimeType': " +
          "the local backend cannot run multi-manager (in-JVM port allocator, child processes)"
      )
    else Right(())
