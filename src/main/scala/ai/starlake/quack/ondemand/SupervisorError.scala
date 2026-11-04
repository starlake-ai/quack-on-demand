package ai.starlake.quack.ondemand

/** Typed error for the [[PoolSupervisor]] mutators.
  *
  * Replaces the old `Either[String, X]` contract where REST handlers classified failures by
  * sniffing the message text (`startsWith("tenant not found")`, `contains("active pool")`,
  * `endsWith("not found")`, ...). Handlers now pattern-match on the case; `message` carries the
  * exact human-readable text the string contract used to emit, so REST response bodies are
  * unchanged.
  *
  * The case set is derived from the error categories the supervisor actually produces:
  *   - [[ai.starlake.quack.ondemand.SupervisorError.NotFound NotFound]]: a referenced entity
  *     (tenant, tenant-db, pool, node, user, role, group, permission, policy) does not exist.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.AlreadyExists AlreadyExists]]: unique-name
  *     collision on create (tenant, tenant-db, role, group).
  *   - [[ai.starlake.quack.ondemand.SupervisorError.Conflict Conflict]]: live dependents block the
  *     mutation (tenant / tenant-db still has active pools).
  *   - [[ai.starlake.quack.ondemand.SupervisorError.InvalidArgument InvalidArgument]]: request
  *     payload failed validation (bad verb, bad authProvider, bad kind contract, cross-tenant
  *     membership edge, invalid transform / predicate SQL, ...).
  *   - [[ai.starlake.quack.ondemand.SupervisorError.InvalidName InvalidName]]: slug normalization
  *     failure from [[ai.starlake.quack.model.Names]]. Kept distinct from
  *     [[ai.starlake.quack.ondemand.SupervisorError.InvalidArgument InvalidArgument]] because the
  *     tenant / tenant-db create endpoints historically map these to `409 exists` (the catch-all),
  *     not `400`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.Internal Internal]]: infrastructure or
  *     control-plane-state failure (Postgres provisioning failed, in-memory pool cache out of sync
  *     with the store). Only ever consumed by catch-all arms.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.BuiltinProtected BuiltinProtected]]: the target
  *     is a built-in role or group ([[ai.starlake.quack.ondemand.state.BuiltinRbac]]); REST 409
  *     `builtin_protected`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.ReservedName ReservedName]]: a create used the
  *     reserved `qod_` prefix; REST 400 `reserved_name`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.InvalidMembership InvalidMembership]]:
  *     user-create roles/groups were empty or named an unknown object; `code` is the REST error
  *     code (`roles_required`, `groups_required`, `unknown_role`, `unknown_group`,
  *     `memberships_not_applicable`).
  *   - [[ai.starlake.quack.ondemand.SupervisorError.DatabaseNameTaken DatabaseNameTaken]]: a create
  *     would use a Postgres database that already holds something; REST 409 `database_name_taken`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.OwnLoginRequired OwnLoginRequired]]: a
  *     metastore on another server than QoD's own lacks its own login; REST 400
  *     `own_login_required`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.DbNameFixed DbNameFixed]]: an update would
  *     change a DuckLake database's `dbName`; REST 400 `db_name_fixed`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.CatalogDatabaseMissing CatalogDatabaseMissing]]:
  *     a create on another server found no catalog database there; REST 400
  *     `catalog_database_missing`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.SuperuserChoice SuperuserChoice]]: a writer who
  *     is not a superuser chose what only a superuser chooses for a database
  *     (`TenantDb.superuserChoiceError`); REST 403 `superuser_required`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.DataPathTaken DataPathTaken]]: the data path
  *     QoD would derive for a new database lies at, under or above another's; REST 409
  *     `data_path_taken`.
  *   - [[ai.starlake.quack.ondemand.SupervisorError.LocalFilesOff LocalFilesOff]]: the database's
  *     data path is local and nodes have local files off (`QOD_NODE_ALLOW_LOCAL_FILES=false`); REST
  *     400 `local_files_off`.
  */
enum SupervisorError(val message: String):
  case NotFound(msg: String)                        extends SupervisorError(msg)
  case AlreadyExists(msg: String)                   extends SupervisorError(msg)
  case Conflict(msg: String)                        extends SupervisorError(msg)
  case InvalidArgument(msg: String)                 extends SupervisorError(msg)
  case InvalidName(msg: String)                     extends SupervisorError(msg)
  case Internal(msg: String)                        extends SupervisorError(msg)
  case QuotaExceeded(msg: String)                   extends SupervisorError(msg)
  case InvalidEmail(msg: String)                    extends SupervisorError(msg)
  case BuiltinProtected(msg: String)                extends SupervisorError(msg)
  case ReservedName(msg: String)                    extends SupervisorError(msg)
  case InvalidMembership(code: String, msg: String) extends SupervisorError(msg)
  case DatabaseNameTaken(msg: String)               extends SupervisorError(msg)
  case OwnLoginRequired(msg: String)                extends SupervisorError(msg)
  case DbNameFixed(msg: String)                     extends SupervisorError(msg)
  case CatalogDatabaseMissing(msg: String)          extends SupervisorError(msg)
  case SuperuserChoice(msg: String)                 extends SupervisorError(msg)
  case DataPathTaken(msg: String)                   extends SupervisorError(msg)
  case LocalFilesOff(msg: String)                   extends SupervisorError(msg)
