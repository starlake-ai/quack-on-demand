package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError, TenantDbPatch}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.initsql.{InitSqlChecker, InitSqlTrust}
import ai.starlake.quack.ondemand.state.FederatedSourceStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditActor, AuditRecorder}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import sttp.model.StatusCode

/** REST handlers for the `qodstate_tenant_db` rows owned by each tenant. Identified by the natural
  * `(tenant, name)` pair; the surrogate `id` ships on responses for rename-stable callers.
  *
  * `federatedStore` is optional: file-mode deployments don't have a federation store wired up, so
  * the count is reported as 0.
  */
final class TenantDbHandlers(
    sup: PoolSupervisor,
    federatedStore: Option[FederatedSourceStore] = None,
    catalog: Option[CatalogHandlers] = None,
    audit: AuditRecorder = AuditRecorder.noop,
    /** Mirrors `manager.managedObjectStore.enabled`: gates `managedStorage` requests here so the
      * caller gets a 400 naming the env var instead of a supervisor-level refusal.
      */
    managedEnabled: Boolean = false,
    /** Mirrors `quack-on-demand.requireEncryption`: refuses an unencrypted create so an operator
      * can guarantee no plaintext database exists in the deployment. Gates creates only; existing
      * databases are untouched, so enabling it never bricks a running deployment.
      */
    requireEncryption: Boolean,
    /** The write-time check of a tenant admin's initSql (qod-node --check-init when this manager
      * runs nodes on its host); the node stays the gate.
      */
    initSqlChecker: InitSqlChecker = InitSqlChecker.unavailable
) extends LazyLogging:

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def redact(m: Map[String, String]): Map[String, String] =
    HandlerResolvers.redactPassword(m)

  /** Rejects any objectStore VALUE containing ';'; delegates the rule and message to
    * [[TenantDb.objectStoreError]] (the model-level check `updateTenantDb`/`validateSafety` also
    * run) so it is authored once. Kept as a thin wrapper here purely so this handler's 400 shape is
    * built at the call sites below.
    */
  private def validateObjectStore(objectStore: Map[String, String]): Option[String] =
    TenantDb.objectStoreError(objectStore)

  /** `400 invalid_metastore_key` for a metastore holding a key outside
    * [[TenantDb.KnownMetastoreKeys]] (`error`, from [[TenantDb.metastoreKeyError]] or, on update,
    * [[TenantDb.metastoreUpdateKeyError]]), before anything is written. A metastore is tenant-admin
    * input, and its keys used to become environment variables of every node's launcher (`BASH_ENV`,
    * `PATH`, `QOD_NODE_BIN`...); the launchers now drop such a key, and the API refuses to store
    * one. A row stored before keeps working, and an update that sends its key back is refused; a
    * secret-named one, which no response shows, is removed by sending it empty.
    */
  private def metastoreKeyRefusal(error: Option[String]): Option[(StatusCode, ErrorResponse)] =
    error.map(msg => (StatusCode.BadRequest, ErrorResponse("invalid_metastore_key", msg)))

  /** The three `managedStorage` refusals, all 400 `invalid`: the deployment has managed storage
    * switched off, the caller mixed managed with a BYO location, or the kind cannot hold one. An
    * unparseable `kind` yields None here so the existing `invalid_kind` arm below still owns that
    * message.
    */
  private def validateManagedStorage(req: TenantDbRequest): Option[String] =
    if !req.managedStorage then None
    else if !managedEnabled then
      Some("managed storage is not enabled on this deployment (QOD_MANAGED_STORE_ENABLED)")
    else if req.dataPath.nonEmpty || req.objectStore.nonEmpty then
      Some("managedStorage is exclusive with dataPath/objectStore: one intent per call")
    else if TenantDbKind.fromWire(req.kind).toOption.exists(_ != TenantDbKind.DuckLake) then
      Some("managedStorage requires kind=ducklake")
    else None

  /** The encryption refusals, all 400 `invalid`. The per-kind rules duplicate `TenantDb`'s own
    * encryption checks deliberately: the model check runs on the constructed row and would reject
    * with the same message, but catching it here gives the caller a 400 with the field named rather
    * than a supervisor-level refusal, exactly as `validateManagedStorage` does.
    */
  private[api] def validateEncryption(req: TenantDbRequest): Option[String] =
    val kind = TenantDbKind.fromWire(req.kind).toOption
    val key  = req.encryptionKey.filter(_.nonEmpty)
    if key.isDefined && kind.contains(TenantDbKind.DuckLake) then
      Some("kind=ducklake manages its own per-file encryption keys: remove encryptionKey")
    else if key.isDefined && !req.encrypted then
      Some("encryptionKey requires encrypted=true: one intent per call")
    else if requireEncryption && kind.contains(TenantDbKind.InMemory) then
      Some(
        "this deployment requires encryption at rest (QOD_REQUIRE_ENCRYPTION): kind=memory " +
          "cannot satisfy it"
      )
    else if requireEncryption && !req.encrypted then
      Some(
        "this deployment requires encryption at rest (QOD_REQUIRE_ENCRYPTION): pass encrypted=true"
      )
    else None

  private def federatedCount(tenantDbId: String): Int =
    federatedStore.fold(0)(_.listSources(tenantDbId).size)

  /** Sum of per-schema table counts via the catalog reader; None on any reader failure: the list
    * call must never fail because a catalog is unreachable. A `duckdb-file` / `memory` catalog
    * lives inside the node, so it is `-1` (unknown, still browsable): counting would cost a node
    * query per database on every list, and wake its pool when suspended.
    */
  private def tableCountFor(tenantName: String, td: TenantDb): Option[Int] =
    if td.kind != TenantDbKind.DuckLake then catalog.map(_ => TenantDbHandlers.UnknownTableCount)
    else
      catalog.flatMap { c =>
        // Unscoped on purpose: the calling handler has already scope-checked
        // the tenant, and this is an in-process aggregation, not a route.
        scala.util.Try(c.listSchemasUnscoped(tenantName, td.name).map(_.tableCount).sum).toOption
      }

  private def toResponse(tenantName: String, td: TenantDb): TenantDbResponse =
    TenantDbResponse(
      id = td.id,
      tenant = tenantName,
      name = td.name,
      kind = td.kind.wireValue,
      metastore = redact(td.metastore),
      dataPath = td.dataPath,
      objectStore = redact(td.objectStore),
      defaultDatabase = td.defaultDatabase,
      defaultSchema = td.defaultSchema,
      disabled = td.disabled,
      federatedSourceCount = federatedCount(td.id),
      initSql = td.initSql,
      effectiveDataPath = sup.effectiveMetastoreFor(tenantName, td.name).getOrElse("dataPath", ""),
      tableCount = tableCountFor(tenantName, td),
      encrypted = td.encrypted,
      initSqlTrusted = td.initSqlTrusted
    )

  /** `400 init_sql_refused` with `message`. */
  private def initSqlRefused(message: String): (StatusCode, ErrorResponse) =
    (StatusCode.BadRequest, ErrorResponse("init_sql_refused", message))

  def createTenantDb(req: TenantDbRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[TenantDbResponse] =
    createTenantDbAs(
      req,
      scopeOf(apiKey),
      AuditActor.Bearer(apiKey)
    )

  /** The one database create path, shared by `POST /api/database/create` and the boot-time
    * `ServeProvisionHook` (which passes `SessionScope.Superuser` and `AuditActor.System`): the
    * tenant-scope gate on the caller's resolved scope, the name, metastore-key, object-store,
    * managed-storage, encryption and kind checks, the create (quota-gated unless the scope is a
    * superuser's), and its audit rows.
    */
  def createTenantDbAs(
      req: TenantDbRequest,
      scope: SessionScope,
      actor: AuditActor
  ): Out[TenantDbResponse] =
    // A tenant admin's initSql is judged by the tenant initSql allowlist once the cheap checks pass
    // (a caller outside the tenant gets its 403 from the scope gate, a malformed request its 400,
    // both unjudged).
    val writerTrusted = SuperuserCheck.forScope(scope).isEmpty
    if TenantScopeCheck.forScope(scope, req.tenant).isDefined ||
      createRefusal(req, writerTrusted).isDefined
    then createChecked(req, scope, actor)
    else
      InitSqlChecker
        .writeCheck(initSqlChecker, writerTrusted, req.initSql, None)
        .flatMap {
          case Left(message)  => IO.pure(Left(initSqlRefused(message)))
          case Right(warning) =>
            createChecked(req, scope, actor).map(_.map(_.copy(initSqlWarning = warning)))
        }

  /** The checks of a database create that need no I/O, in the order the create applies them. */
  private def createRefusal(
      req: TenantDbRequest,
      writerTrusted: Boolean
  ): Option[(StatusCode, ErrorResponse)] =
    if req.tenant.isEmpty || req.name.isEmpty then
      Some((StatusCode.BadRequest, ErrorResponse("invalid", "tenant and name must be non-empty")))
    else
      metastoreKeyRefusal(TenantDb.metastoreKeyError(req.metastore))
        .orElse(InitSqlTrust.markerRefusal(req.initSql).map(initSqlRefused))
        .orElse(InitSqlTrust.sizeRefusal(req.initSql, writerTrusted).map(initSqlRefused))
        .orElse(
          validateObjectStore(req.objectStore)
            .orElse(validateManagedStorage(req))
            .orElse(validateEncryption(req))
            .map(msg => (StatusCode.BadRequest, ErrorResponse("invalid", msg)))
        )
        .orElse(
          TenantDbKind
            .fromWire(req.kind)
            .left
            .toOption
            .map(err => (StatusCode.BadRequest, ErrorResponse("invalid_kind", err)))
        )

  private def createChecked(
      req: TenantDbRequest,
      scope: SessionScope,
      actor: AuditActor
  ): Out[TenantDbResponse] =
    TenantScopeCheck.forScope(scope, req.tenant) match
      case Some(err) =>
        actor.controlPlane(audit, AuditActions.DatabaseCreate, "denied", tenant = Some(req.tenant))
        IO.pure(Left(err))
      case None =>
        createRefusal(req, SuperuserCheck.forScope(scope).isEmpty) match
          case Some(err) =>
            IO.pure(Left(err))
          case None =>
            TenantDbKind.fromWire(req.kind) match
              case Left(err) =>
                IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid_kind", err))))
              case Right(kind) =>
                val gateBypass = SuperuserCheck.forScope(scope).isEmpty
                sup
                  .createTenantDb(
                    tenantName = req.tenant,
                    suffix = req.name,
                    kind = kind,
                    metastore = req.encryptionKey.filter(_.nonEmpty).fold(req.metastore) { k =>
                      req.metastore.updated(TenantDb.EncryptionKeyName, k)
                    },
                    dataPath = req.dataPath,
                    objectStore = req.objectStore,
                    defaultDatabase = req.defaultDatabase,
                    defaultSchema = req.defaultSchema,
                    initSql = req.initSql,
                    initSqlTrusted = gateBypass,
                    managedStorage = req.managedStorage,
                    encrypted = req.encrypted,
                    gateBypass = gateBypass
                  )
                  .flatMap {
                    case Right(td) =>
                      if req.encryptionKey.exists(_.nonEmpty) then
                        logger.warn(
                          s"tenant-db ${td.name} was created with a caller-supplied encryption " +
                            "key. QoD will never return it: if it is lost the database cannot " +
                            "be opened."
                        )
                      actor.controlPlane(
                        audit,
                        AuditActions.DatabaseCreate,
                        "ok",
                        tenant = Some(req.tenant),
                        target = Some(td.name),
                        detail = Map("kind" -> kind.wireValue)
                      )
                      IO.blocking(Right(toResponse(req.tenant, td)))
                    case Left(err: SupervisorError.NotFound) =>
                      IO.pure(
                        Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
                      )
                    case Left(err: SupervisorError.InvalidArgument) =>
                      IO.pure(
                        Left(
                          (StatusCode.BadRequest, ErrorResponse("invalid_contract", err.message))
                        )
                      )
                    case Left(err: SupervisorError.QuotaExceeded) =>
                      actor.controlPlane(
                        audit,
                        AuditActions.DatabaseCreate,
                        "denied",
                        tenant = Some(req.tenant),
                        detail = Map("reason" -> "quota")
                      )
                      IO.pure(
                        Left(
                          (
                            StatusCode.TooManyRequests,
                            ErrorResponse("quota_exceeded", err.message)
                          )
                        )
                      )
                    // Not a conflict: Postgres provisioning failed or the DuckLake pre-init
                    // refused the catalog (already logged by the supervisor).
                    case Left(err: SupervisorError.Internal) =>
                      IO.pure(
                        Left(
                          (StatusCode.InternalServerError, ErrorResponse("internal", err.message))
                        )
                      )
                    case Left(err) =>
                      IO.pure(Left((StatusCode.Conflict, ErrorResponse("exists", err.message))))
                  }

  def listTenantDbs(tenant: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[TenantDbListResponse] =
    // Resolve to the tenant id before the scope check (the query param may be a
    // display name); an unknown tenant falls through on the raw value, so an
    // out-of-scope session gets the same 403 whether the tenant exists or not
    // (no existence leak) while a superuser keeps the empty-list response.
    val tid = sup.getTenantById(tenant).orElse(sup.getTenant(tenant)).map(_.id).getOrElse(tenant)
    TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        IO.blocking {
          Right(
            TenantDbListResponse(
              sup.listTenantDbsByTenant(tenant).map(td => toResponse(tenant, td))
            )
          )
        }

  /** Resolved defaultMetastore connection values for the admin UI's create form. Any admin caller
    * (tenant or system, session or PAT) may read it: `ManagerServer.apiKeyGuard` already rejects
    * unauthenticated callers and demotes profile sessions, and the values are deployment-wide
    * (tenant admins already see pgHost/pgUser on redacted database/list rows). `pgPassword` is
    * absent from the response type by construction.
    */
  def metastoreDefaults(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[MetastoreDefaultsResponse] =
    IO.pure {
      val d = sup.metastoreDefaults
      Right(
        MetastoreDefaultsResponse(
          pgHost = d.getOrElse("pgHost", ""),
          pgPort = d.getOrElse("pgPort", ""),
          pgUser = d.getOrElse("pgUser", ""),
          schemaName = d.getOrElse("schemaName", "")
        )
      )
    }

  def deleteTenantDb(req: TenantDbOpRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.DatabaseDelete,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        sup.deleteTenantDb(req.tenant, req.name, purgeManagedData = req.purgeManagedData).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.DatabaseDelete,
              "ok",
              tenant = Some(req.tenant),
              target = Some(req.name)
            )
            Right(())
          case Left(err: SupervisorError.Conflict) =>
            Left((StatusCode.Conflict, ErrorResponse("has_pools", err.message)))
          case Left(err) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
        }

  def update(req: UpdateTenantDbRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[UpdateTenantDbResponse] =
    // A tenant admin's changed initSql is judged once the cheap checks pass (see createTenantDbAs).
    val writerTrusted = SuperuserCheck.forScope(scopeOf(apiKey)).isEmpty
    (req.initSql, TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf)) match
      case (Some(sql), None) if updateRefusal(req, writerTrusted).isEmpty =>
        InitSqlChecker
          .writeCheck(
            initSqlChecker,
            writerTrusted,
            sql,
            sup.findTenantDb(req.tenant, req.name).map(_.initSql)
          )
          .flatMap {
            case Left(message)  => IO.pure(Left(initSqlRefused(message)))
            case Right(warning) =>
              updateChecked(req, apiKey)(scopeOf).map(
                _.map(r => r.copy(db = r.db.copy(initSqlWarning = warning)))
              )
          }
      case _ => updateChecked(req, apiKey)(scopeOf)

  /** The checks of a database update that need no I/O, in the order the update applies them. */
  private def updateRefusal(
      req: UpdateTenantDbRequest,
      writerTrusted: Boolean
  ): Option[(StatusCode, ErrorResponse)] =
    req.metastore
      .flatMap(m => metastoreKeyRefusal(TenantDb.metastoreUpdateKeyError(m)))
      .orElse(req.initSql.flatMap(InitSqlTrust.markerRefusal).map(initSqlRefused))
      .orElse(
        req.initSql.flatMap(InitSqlTrust.sizeRefusal(_, writerTrusted)).map(initSqlRefused)
      )
      .orElse(
        req.objectStore
          .flatMap(validateObjectStore)
          .map(msg => (StatusCode.BadRequest, ErrorResponse("invalid", msg)))
      )

  private def updateChecked(req: UpdateTenantDbRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[UpdateTenantDbResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.DatabaseUpdate,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        updateRefusal(req, SuperuserCheck.forScope(scopeOf(apiKey)).isEmpty) match
          case Some(err) =>
            IO.pure(Left(err))
          case None =>
            val patch = TenantDbPatch(
              metastore = req.metastore,
              objectStore = req.objectStore,
              defaultDatabase = req.defaultDatabase,
              defaultSchema = req.defaultSchema,
              initSql = req.initSql,
              initSqlWriterTrusted = SuperuserCheck.forScope(scopeOf(apiKey)).isEmpty
            )
            sup.updateTenantDb(req.tenant, req.name, patch).flatMap {
              case Right(r) =>
                // Record field names ONLY (never values - metastore / objectStore may contain credentials).
                val editedFields = List(
                  req.metastore.map(_ => "metastore"),
                  req.objectStore.map(_ => "objectStore"),
                  req.defaultDatabase.map(_ => "defaultDatabase"),
                  req.defaultSchema.map(_ => "defaultSchema"),
                  req.initSql.map(_ => "initSql")
                ).flatten.mkString(",")
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.DatabaseUpdate,
                  "ok",
                  tenant = Some(req.tenant),
                  target = Some(req.name),
                  detail =
                    if editedFields.nonEmpty then Map("editedFields" -> editedFields) else Map.empty
                )
                IO.blocking(
                  Right(
                    UpdateTenantDbResponse(
                      db = toResponse(req.tenant, r.td),
                      restartedNodes = r.restartedNodes,
                      failedRestarts = r.failedRestarts.map(FailedRestart.apply.tupled)
                    )
                  )
                )
              case Left(err: SupervisorError.InvalidArgument) =>
                IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid", err.message))))
              case Left(err) =>
                IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", err.message))))
            }

object TenantDbHandlers:

  /** `tableCount` of a database whose catalog lives inside its node (`duckdb-file`, `memory`): not
    * counted, but browsable through the catalog endpoints.
    */
  val UnknownTableCount: Int = -1
