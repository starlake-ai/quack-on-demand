package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.{EngineStatsTracker, NodeLoadTracker}
import ai.starlake.quack.model.{AutoscaleBand, LockdownTriState, PoolKey, QuantitySyntax}
import ai.starlake.quack.ondemand.{PoolState, PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.initsql.{InitSqlChecker, InitSqlTrust}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditActor, AuditRecorder}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import sttp.model.StatusCode

final class PoolHandlers(
    sup: PoolSupervisor,
    tracker: NodeLoadTracker,
    engineStats: EngineStatsTracker = new EngineStatsTracker,
    podTemplateEnabled: Boolean = false,
    // Upper bound accepted for maxNodes at validation time (autoscale.hardCap):
    // a typo guard, not a quota.
    autoscaleHardCap: Int = 16,
    audit: AuditRecorder = AuditRecorder.noop,
    // Declared-but-unattached Iceberg catalogs for one node INCARNATION, from the in-memory
    // AttachStatusRegistry (Task 7). Inert default so every existing caller/test keeps compiling;
    // Main wires it from the registry, keyed on (nodeId, startedAt) so a respawned node under the
    // same slot id does not inherit its predecessor's failures. Called UNGUARDED below, like the
    // load/latency/engine lookups beside it: this is the surface whose whole purpose is to make a
    // silent attach failure visible, so degrading a broken lookup into "no failures" would
    // reproduce exactly the bug the feature exists to catch. A lookup that can throw belongs
    // guarded (and logged) at the wiring site, where the failure can still be said out loud.
    attachFailuresOf: (String, java.time.Instant) => List[CatalogAttachFailureDto] = (_, _) => Nil,
    /** The write-time check of a tenant admin's initSql (see TenantDbHandlers). */
    initSqlChecker: InitSqlChecker = InitSqlChecker.unavailable
) extends LazyLogging:

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  /** Exposed for `/api/config/client` so the UI can hide the placement controls in non-K8s mode.
    */
  def supportsPlacement: Boolean = sup.supportsPlacement

  /** Exposed for `/api/config/client` so the UI shows the Fleet servers page only when the runtime
    * backend is fleet.
    */
  def fleetEnabled: Boolean = sup.fleetEnabled

  /** Hide secret-like keys from the API response: see
    * [[ai.starlake.quack.model.TenantDb.SecretKeys]] (`pgPassword` plus the object-store secret
    * keys).
    */
  private def redact(metastore: Map[String, String]): Map[String, String] =
    HandlerResolvers.redactPassword(metastore)

  /** Fleet server liveness by name, read ONCE per listing request off the compute pool. Any error
    * degrades to empty (serverState None): server state is decoration, the listing must not fail
    * because the fleet table is unreadable.
    */
  private def serverLiveness: IO[Map[String, String]] =
    IO.blocking(sup.serverLivenessAll()).handleError { t =>
      logger.warn(s"fleet server liveness lookup failed, listing without it: $t", t)
      Map.empty
    }

  /** Build the response for an existing pool by looking up its supervisor state. `liveness` is the
    * fleet server liveness by name (from [[serverLiveness]]); pass `Map.empty` where the response
    * does not need server state (mutation replies).
    */
  private def respond(key: PoolKey, liveness: Map[String, String]): Option[PoolResponse] =
    sup.get(key).map { p =>
      val poolEntityCohorts = sup.poolId(key).flatMap(sup.poolEntity).map(_.cohorts).getOrElse(Nil)
      // Same pool-row resolution as the lockdown fields below; None on a fixed-size pool.
      val band = sup.autoscaleBand(key)
      PoolResponse(
        tenant = key.tenant,
        tenantDb = key.tenantDb,
        pool = key.pool,
        nodes = p.nodes.map { n =>
          val load            = tracker.snapshot(n.nodeId)
          val (p50, p95, p99) = tracker.latencyPercentiles(n.nodeId)
          val engine          = engineStats.snapshot(n.nodeId)
          NodeInfo(
            nodeId = n.nodeId,
            role = n.role.toString,
            host = n.host,
            port = n.port,
            maxConcurrent = n.maxConcurrent,
            inFlight = load.inFlight,
            totalServed = load.totalServed,
            avgDurationMs = load.ewmaMs,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            healthy = load.healthy,
            draining = load.draining,
            quarantined = load.quarantined,
            duckdbMemoryBytes = engine.map(_.memoryUsedBytes),
            duckdbTempStorageBytes = engine.map(_.tempStorageBytes),
            duckdbSpillFiles = engine.map(_.spillFiles),
            duckdbSpillBytes = engine.map(_.spillBytes),
            catalogAttachFailures = attachFailuresOf(n.nodeId, n.startedAt),
            serverName = n.serverName,
            serverState = n.serverName.flatMap(liveness.get)
          )
        },
        status = PoolHandlers.statusOf(p),
        metastore = redact(p.metastore),
        disabled = p.disabled,
        suspended = p.suspended,
        id = sup.poolId(key).getOrElse(""),
        cohorts = poolEntityCohorts.map(PoolCohortDto.fromModel),
        initSql = p.initSql,
        cpu = p.cpu,
        memory = p.memory,
        lockdown = LockdownTriState.render(
          sup.poolId(key).flatMap(sup.poolEntity).flatMap(_.lockdown)
        ),
        lockdownEffective = sup.effectiveLockdown(key),
        minNodes = band.map(_._1),
        maxNodes = band.map(_._2),
        pending = sup.pendingCount(key),
        pendingReason = sup.pendingReason(key),
        pendingDetail = sup.pendingDetail(key),
        initSqlTrusted = sup.poolId(key).flatMap(sup.poolEntity).exists(_.initSqlTrusted)
      )
    }

  def createPool(req: CreatePoolRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    createPoolAs(
      req,
      scopeOf(apiKey),
      AuditActor.Bearer(apiKey)
    )

  /** The one pool create path, shared by `POST /api/pool/create` and the boot-time
    * `ServeProvisionHook` (which passes `SessionScope.Superuser` and `AuditActor.System`): the
    * tenant-scope gate and the superuser-only lockdown and pod-template gates on the caller's
    * resolved scope, the shape checks, the create (quota-gated unless the scope is a superuser's),
    * and its audit rows.
    */
  def createPoolAs(
      req: CreatePoolRequest,
      scope: SessionScope,
      actor: AuditActor
  ): Out[PoolResponse] =
    TenantScopeCheck.forScope(scope, req.tenant) match
      case Some(err) =>
        actor.controlPlane(audit, AuditActions.PoolCreate, "denied", tenant = Some(req.tenant))
        IO.pure(Left(err))
      case None =>
        val gateBypass = SuperuserCheck.forScope(scope).isEmpty
        // Per-pool lockdown override is a superuser-only mutation (same rule as
        // POST /api/pool/setLockdown). Gate it at create time BEFORE any state
        // change so a tenant admin cannot slip "lockdown": "on"|"off" past the
        // TenantScopeCheck. "inherit" (the default) stays open to tenant admins.
        // Deliberate quirk: this compares the raw string, before
        // LockdownTriState.parse runs in createPoolInner, so a tenant admin
        // sending an INVALID value (e.g. "banana") also gets 403
        // superuser_required rather than 400 invalid -- fail-closed on any
        // non-"inherit" string, not just the two valid overrides.
        val lockdownGate =
          if req.lockdown != "inherit" then SuperuserCheck.forScope(scope) else None
        lockdownGate match
          case Some(err) =>
            actor.controlPlane(audit, AuditActions.PoolCreate, "denied", tenant = Some(req.tenant))
            IO.pure(Left(err))
          case None =>
            if req.podTemplateYaml.nonEmpty then
              SuperuserCheck.forScope(scope) match
                case Some(err) =>
                  actor.controlPlane(
                    audit,
                    AuditActions.PoolCreate,
                    "denied",
                    tenant = Some(req.tenant)
                  )
                  IO.pure(Left(err))
                case None =>
                  if !podTemplateEnabled then
                    IO.pure(
                      Left(
                        (
                          StatusCode.BadRequest,
                          ErrorResponse(
                            "feature_disabled",
                            "pod templates are disabled (QOD_POD_TEMPLATE_ENABLED=false)"
                          )
                        )
                      )
                    )
                  else
                    QuantitySyntax.validPodTemplate(req.podTemplateYaml) match
                      case Left(msg) =>
                        IO.pure(
                          Left((StatusCode.BadRequest, ErrorResponse("invalid_template", msg)))
                        )
                      case Right(()) => createPoolInner(req, actor, gateBypass)
            else createPoolInner(req, actor, gateBypass)

  private def createPoolInner(
      req: CreatePoolRequest,
      actor: AuditActor,
      gateBypass: Boolean
  ): Out[PoolResponse] =
    // Band shape (one-sided, min >= max, hardCap, write-capable floor, size inside
    // the band) plus the cohort conflict: autoscaling rewrites the role
    // distribution, which clears authored cohorts, so the two cannot be declared
    // together.
    val bandShapeErr =
      AutoscaleBand
        .validate(req.minNodes, req.maxNodes, req.roleDistribution, req.size, autoscaleHardCap)
    val bandErr =
      if req.minNodes.isDefined && req.cohorts.nonEmpty then
        Some(
          bandShapeErr.getOrElse("elastic pools cannot use authored cohorts (scaling clears them)")
        )
      else bandShapeErr
    // The marker text, and a tenant admin's initSql over the size cap.
    val initSqlRefusal = req.initSql.flatMap(sql =>
      InitSqlTrust.markerRefusal(sql).orElse(InitSqlTrust.sizeRefusal(sql, gateBypass))
    )
    LockdownTriState.parse(req.lockdown) match
      case Left(msg) =>
        IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid", msg))))
      case Right(lockdownValue) =>
        if req.cpu.nonEmpty && !QuantitySyntax.validCpu(req.cpu) then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid", "cpu/memory must be Kubernetes quantities")
              )
            )
          )
        else if req.memory.nonEmpty && !QuantitySyntax.validMemory(req.memory) then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid", "cpu/memory must be Kubernetes quantities")
              )
            )
          )
        else if !req.roleDistribution.isValidFor(req.size) then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid_distribution", "role counts do not sum to size")
              )
            )
          )
        else if initSqlRefusal.isDefined then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("init_sql_refused", initSqlRefusal.getOrElse(""))
              )
            )
          )
        else if bandErr.isDefined then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid_band", bandErr.getOrElse("invalid autoscale band"))
              )
            )
          )
        else
          val key = PoolKey(req.tenant, req.tenantDb, req.pool)
          sup.getTenant(req.tenant) match
            case None =>
              IO.pure(
                Left(
                  (
                    StatusCode.NotFound,
                    ErrorResponse("tenant_not_found", s"tenant '${req.tenant}' is not registered")
                  )
                )
              )
            case Some(_) if sup.findTenantDb(req.tenant, req.tenantDb).isEmpty =>
              IO.pure(
                Left(
                  (
                    StatusCode.NotFound,
                    ErrorResponse(
                      "tenant_db_not_found",
                      s"tenant-db '${req.tenant}/${req.tenantDb}' is not registered"
                    )
                  )
                )
              )
            case Some(_) =>
              sup.get(key) match
                case Some(_) =>
                  IO.pure(
                    Left(
                      (StatusCode.Conflict, ErrorResponse("exists", s"pool $key already exists"))
                    )
                  )
                case None =>
                  // A tenant admin's initSql is judged by the tenant initSql allowlist now that every
                  // cheap check passed; a superuser's is not.
                  InitSqlChecker
                    .writeCheck(initSqlChecker, gateBypass, req.initSql.getOrElse(""), None)
                    .flatMap {
                      case Left(message) =>
                        IO.pure(
                          Left((StatusCode.BadRequest, ErrorResponse("init_sql_refused", message)))
                        )
                      case Right(warning) =>
                        val cohorts = req.cohorts.map(PoolCohortDto.toModel)
                        sup
                          .createPool(
                            key,
                            req.roleDistribution,
                            maxConcurrentPerNode = req.maxConcurrentPerNode,
                            cohorts = cohorts,
                            disabled = req.disabled,
                            startSuspended = req.startSuspended,
                            initSql = req.initSql.getOrElse(""),
                            initSqlTrusted = gateBypass,
                            cpu = req.cpu,
                            memory = req.memory,
                            podTemplateYaml = req.podTemplateYaml,
                            lockdown = lockdownValue,
                            minNodes = req.minNodes,
                            maxNodes = req.maxNodes,
                            // -1 is the DTO's "not provided" sentinel: inherit the manager-wide
                            // hibernation default. 0 opts the pool out; positive sets its window.
                            idleTimeoutSec = Some(req.idleTimeoutSec).filter(_ >= 0),
                            gateBypass = gateBypass
                          )
                          .map(_ =>
                            actor.controlPlane(
                              audit,
                              AuditActions.PoolCreate,
                              "ok",
                              tenant = Some(req.tenant),
                              target = Some(key.toString),
                              detail = Map("size" -> req.size.toString)
                            )
                            Right(
                              respond(key, Map.empty).getOrElse(
                                PoolResponse(
                                  req.tenant,
                                  req.tenantDb,
                                  req.pool,
                                  Nil,
                                  "ready",
                                  Map.empty
                                )
                              )
                            )
                          )
                          .handleError {
                            case q: ai.starlake.quack.spi.QuotaExceededException =>
                              Left(
                                (
                                  StatusCode.TooManyRequests,
                                  ErrorResponse("quota_exceeded", q.reason)
                                )
                              )
                            case t =>
                              // The supervisor logs nothing when the first node fails to start.
                              logger.error(s"pool create for $key failed", t)
                              Left(
                                (
                                  StatusCode.InternalServerError,
                                  ErrorResponse("start_failed", t.getMessage)
                                )
                              )
                          }
                          .map(_.map(_.copy(initSqlWarning = warning)))
                    }

  def scalePool(req: ScalePoolRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolScale,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val gateBypass = SuperuserCheck.reject(apiKey)(scopeOf).isEmpty
        val key        = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.get(key) match
          case None =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case Some(st) if st.suspended =>
            IO.pure(
              Left(
                (
                  StatusCode.Conflict,
                  ErrorResponse(
                    "pool_suspended",
                    s"pool $key is suspended; resume it before scaling"
                  )
                )
              )
            )
          case Some(_) =>
            if !req.roleDistribution.isValidFor(req.targetSize) then
              IO.pure(
                Left(
                  (
                    StatusCode.BadRequest,
                    ErrorResponse("invalid_distribution", "role counts do not sum to targetSize")
                  )
                )
              )
            // A pool with a declared band stays inside it: a manual scale outside
            // would be undone by the next autoscale sweep, so refuse it and point
            // at the band editor instead.
            else
              sup.autoscaleBand(key) match
                case Some((mn, mx)) if req.targetSize < mn || req.targetSize > mx =>
                  IO.pure(
                    Left(
                      (
                        StatusCode.BadRequest,
                        ErrorResponse(
                          "outside_band",
                          s"targetSize ${req.targetSize} is outside the autoscale band " +
                            s"[$mn, $mx]; adjust the band first via pool/setAutoscale"
                        )
                      )
                    )
                  )
                case _ =>
                  sup
                    .scale(
                      key,
                      req.targetSize,
                      req.roleDistribution,
                      req.force,
                      gateBypass = gateBypass
                    )
                    .attempt
                    .flatMap {
                      case Left(q: ai.starlake.quack.spi.QuotaExceededException) =>
                        IO.pure(
                          Left(
                            (StatusCode.TooManyRequests, ErrorResponse("quota_exceeded", q.reason))
                          )
                        )
                      case Left(t)  => IO.raiseError(t)
                      case Right(_) =>
                        audit.rest(
                          apiKey,
                          "control-plane",
                          AuditActions.PoolScale,
                          "ok",
                          tenant = Some(req.tenant),
                          target = Some(key.toString),
                          detail = Map("targetSize" -> req.targetSize.toString)
                        )
                        IO.pure(
                          Right(
                            respond(key, Map.empty).getOrElse(
                              PoolResponse(
                                req.tenant,
                                req.tenantDb,
                                req.pool,
                                Nil,
                                "ready",
                                Map.empty
                              )
                            )
                          )
                        )
                    }

  def stopPool(req: StopPoolRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolStop,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val key = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.get(key) match
          case None =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case Some(_) =>
            sup
              .stopPool(key, req.force)
              .map(_ =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.PoolStop,
                  "ok",
                  tenant = Some(req.tenant),
                  target = Some(key.toString)
                )
                Right(())
              )

  def deletePool(req: DeletePoolRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolDelete,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val key = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.get(key) match
          case None =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case Some(_) =>
            sup
              .deletePool(key, req.force)
              .map(_ =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.PoolDelete,
                  "ok",
                  tenant = Some(req.tenant),
                  target = Some(key.toString)
                )
                Right(())
              )

  def suspendPool(req: SuspendPoolRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    poolAction(apiKey, req.tenant, AuditActions.PoolSuspend, scopeOf)(
      PoolKey(req.tenant, req.tenantDb, req.pool)
    )(key => sup.suspendPool(key, "rest"))

  def resumePool(req: ResumePoolRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    poolAction(apiKey, req.tenant, AuditActions.PoolResume, scopeOf)(
      PoolKey(req.tenant, req.tenantDb, req.pool)
    )(key => sup.resumePool(key, "rest"))

  /** Shared scope-check + audit + 404 shell for the suspend/resume pair. */
  private def poolAction(
      apiKey: Option[String],
      tenant: String,
      action: String,
      scopeOf: SessionScope.Resolver
  )(key: PoolKey)(
      run: PoolKey => IO[Either[SupervisorError, ai.starlake.quack.model.Pool]]
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(apiKey, "control-plane", action, "denied", tenant = Some(tenant))
        IO.pure(Left(err))
      case None =>
        sup.get(key) match
          case None =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case Some(_) =>
            run(key).map {
              case Left(e) =>
                Left((StatusCode.InternalServerError, ErrorResponse("error", e.toString)))
              case Right(_) =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  action,
                  "ok",
                  tenant = Some(tenant),
                  target = Some(key.toString)
                )
                Right(())
            }

  def listPools(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolListResponse] = serverLiveness.map { liveness =>
    val all      = sup.list().flatMap(p => respond(p.key, liveness))
    val scope    = scopeOf(apiKey)
    val filtered =
      if scope.superuser then all
      else
        // PoolResponse.tenant carries the tenant id (PoolKey.tenant), so filter
        // directly against the manageable tenant-id set. Display names are
        // free-form labels and may differ from the id.
        all.filter(p => scope.manageableTenants.contains(p.tenant))
    Right(PoolListResponse(filtered))
  }

  def poolStatus(tenant: String, tenantDb: String, pool: String): Out[PoolResponse] =
    val key = PoolKey(tenant, tenantDb, pool)
    serverLiveness.map { liveness =>
      respond(key, liveness) match
        case Some(r) => Right(r)
        case None => Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found")))
    }

  def setPoolDisabled(req: SetPoolDisabledRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolSetDisabled,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val key = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.setPoolDisabled(key, req.disabled).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.PoolSetDisabled,
              "ok",
              tenant = Some(req.tenant),
              target = Some(key.toString),
              detail = Map("disabled" -> req.disabled.toString)
            )
            respond(key, Map.empty) match
              case Some(r) => Right(r)
              case None    =>
                Left(
                  (
                    StatusCode.NotFound,
                    ErrorResponse("not_found", s"pool $key disappeared after update")
                  )
                )
          case Left(err: SupervisorError.NotFound) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
          case Left(err) =>
            Left((StatusCode.Conflict, ErrorResponse("update_failed", err.message)))
        }

  /** POST /api/pool/setInitSql (CONTRACT Addendum 4 item 27): the tenant-scope gate, the marker and
    * size checks, then, for a tenant admin's changed text, the tenant initSql allowlist's
    * write-time check (`400 init_sql_refused`, or accepted with `initSqlWarning` where this manager
    * cannot check); a superuser's write is never checked and is stored trusted.
    */
  def setInitSql(req: SetPoolInitSqlRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolSetInitSql,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val key           = PoolKey(req.tenant, req.tenantDb, req.pool)
        val writerTrusted = SuperuserCheck.reject(apiKey)(scopeOf).isEmpty
        val stored        = sup.poolId(key).flatMap(sup.poolEntity).map(_.initSql)
        InitSqlTrust
          .markerRefusal(req.initSql)
          .orElse(InitSqlTrust.sizeRefusal(req.initSql, writerTrusted)) match
          case Some(msg) =>
            IO.pure(Left((StatusCode.BadRequest, ErrorResponse("init_sql_refused", msg))))
          case None if stored.isEmpty =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case None =>
            InitSqlChecker
              .writeCheck(initSqlChecker, writerTrusted, req.initSql, stored)
              .flatMap {
                case Left(message) =>
                  IO.pure(Left((StatusCode.BadRequest, ErrorResponse("init_sql_refused", message))))
                case Right(warning) =>
                  sup.setPoolInitSql(key, req.initSql, writerTrusted).map {
                    case Left(err) =>
                      Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
                    case Right(_) =>
                      // The field name only, never the SQL: init SQL may carry credentials.
                      audit.rest(
                        apiKey,
                        "control-plane",
                        AuditActions.PoolSetInitSql,
                        "ok",
                        tenant = Some(req.tenant),
                        target = Some(key.toString)
                      )
                      respond(key, Map.empty) match
                        case Some(r) => Right(r.copy(initSqlWarning = warning))
                        case None    =>
                          Left(
                            (
                              StatusCode.NotFound,
                              ErrorResponse("not_found", s"pool $key disappeared after update")
                            )
                          )
                  }
              }

  def setResources(req: SetPoolResourcesRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolSetResources,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        if req.cpu.nonEmpty && !QuantitySyntax.validCpu(req.cpu) then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid", s"invalid cpu quantity: '${req.cpu}'")
              )
            )
          )
        else if req.memory.nonEmpty && !QuantitySyntax.validMemory(req.memory) then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid", s"invalid memory quantity: '${req.memory}'")
              )
            )
          )
        else
          val key        = PoolKey(req.tenant, req.tenantDb, req.pool)
          val gateBypass = SuperuserCheck.reject(apiKey)(scopeOf).isEmpty
          sup.setPoolResources(key, req.cpu, req.memory, gateBypass = gateBypass).map {
            case Left(q: SupervisorError.QuotaExceeded) =>
              Left((StatusCode.TooManyRequests, ErrorResponse("quota_exceeded", q.message)))
            case Left(err) =>
              Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
            case Right(_) =>
              val cpuDetail    = if req.cpu.nonEmpty then Map("cpu" -> req.cpu) else Map.empty
              val memoryDetail =
                if req.memory.nonEmpty then Map("memory" -> req.memory) else Map.empty
              audit.rest(
                apiKey,
                "control-plane",
                AuditActions.PoolSetResources,
                "ok",
                tenant = Some(req.tenant),
                target = Some(key.toString),
                detail = cpuDetail ++ memoryDetail
              )
              respond(key, Map.empty) match
                case Some(r) => Right(r)
                case None    =>
                  Left(
                    (
                      StatusCode.NotFound,
                      ErrorResponse("not_found", s"pool $key disappeared after update")
                    )
                  )
          }

  def setPodTemplate(req: SetPoolTemplateRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolSetPodTemplate,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        if !podTemplateEnabled then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse(
                  "feature_disabled",
                  "pod template support is not enabled on this manager"
                )
              )
            )
          )
        else
          val validationResult =
            if req.podTemplateYaml.isEmpty then Right(())
            else QuantitySyntax.validPodTemplate(req.podTemplateYaml)
          validationResult match
            case Left(msg) =>
              IO.pure(
                Left((StatusCode.BadRequest, ErrorResponse("invalid_template", msg)))
              )
            case Right(()) =>
              val key = PoolKey(req.tenant, req.tenantDb, req.pool)
              sup.setPoolTemplate(key, req.podTemplateYaml).map {
                case Left(err) =>
                  Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
                case Right(_) =>
                  // Never include the template body in detail (may contain secrets/paths).
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.PoolSetPodTemplate,
                    "ok",
                    tenant = Some(req.tenant),
                    target = Some(key.toString)
                  )
                  respond(key, Map.empty) match
                    case Some(r) => Right(r)
                    case None    =>
                      Left(
                        (
                          StatusCode.NotFound,
                          ErrorResponse("not_found", s"pool $key disappeared after update")
                        )
                      )
              }

  def setLockdown(req: SetPoolLockdownRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolSetLockdown,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        LockdownTriState.parse(req.lockdown) match
          case Left(msg) =>
            IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid", msg))))
          case Right(value) =>
            val key = PoolKey(req.tenant, req.tenantDb, req.pool)
            sup.setPoolLockdown(key, value).flatMap {
              case Left(err: SupervisorError.NotFound) =>
                IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", err.message))))
              case Left(err) =>
                IO.pure(Left((StatusCode.Conflict, ErrorResponse("update_failed", err.message))))
              case Right(_) =>
                // Restart every node so the spawn-time engine layer catches up
                // immediately (same posture as database/update metastore edits).
                // Per-node failures are tolerated: the flag is persisted and the
                // edge LockdownScreen still enforces the restriction per
                // statement. Reconcile heals DEAD nodes, not a live straggler
                // that failed to restart -- such a node keeps its stale engine
                // SQL until it next respawns (scale, crash-recovery, or a manual
                // restart). A failed restart is logged (not just swallowed) so an
                // operator can spot the straggler instead of discovering the stale
                // SQL later; the request itself must still succeed either way.
                val nodeIds = sup.get(key).map(_.nodes.map(_.nodeId)).getOrElse(Nil)
                import cats.syntax.all.*
                nodeIds
                  .traverse_ { id =>
                    sup.restartNode(key, id).attempt.flatMap {
                      case Right(Right(_))  => IO.unit
                      case Right(Left(err)) =>
                        IO.delay(
                          logger.warn(
                            s"setLockdown: restart of $key/$id failed, node keeps its stale " +
                              s"engine SQL until it next respawns: ${err.message}"
                          )
                        )
                      case Left(t) =>
                        IO.delay(
                          logger.warn(
                            s"setLockdown: restart of $key/$id raised ${t.getClass.getSimpleName}, " +
                              s"node keeps its stale engine SQL until it next respawns: ${t.getMessage}"
                          )
                        )
                    }
                  }
                  .map { _ =>
                    audit.rest(
                      apiKey,
                      "control-plane",
                      AuditActions.PoolSetLockdown,
                      "ok",
                      tenant = Some(req.tenant),
                      target = Some(key.toString),
                      detail = Map("lockdown" -> req.lockdown)
                    )
                    respond(key, Map.empty) match
                      case Some(r) => Right(r)
                      case None    =>
                        Left(
                          (
                            StatusCode.NotFound,
                            ErrorResponse("not_found", s"pool $key disappeared after update")
                          )
                        )
                  }
            }

  /** Sets or clears the pool's demand scale-out band. Both bounds absent clears it back to a fixed
    * size; a declared band is validated against the pool's CURRENT distribution and size, so an
    * operator whose band would exclude today's size scales the pool first, then declares the band.
    * The write itself does NOT move any node: the sweep converges the pool on its next tick.
    */
  def setPoolAutoscale(req: SetPoolAutoscaleRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolSetAutoscale,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val key     = PoolKey(req.tenant, req.tenantDb, req.pool)
        val bandErr = (req.minNodes, req.maxNodes) match
          case (None, None) => None // clear
          case _            =>
            sup.get(key) match
              // Unknown pool: skip validation and let the supervisor answer with
              // its NotFound, so a bad key never leaks as a 400 band error.
              case None     => None
              case Some(st) =>
                val poolEntityCohorts =
                  sup.poolId(key).flatMap(sup.poolEntity).map(_.cohorts).getOrElse(Nil)
                if poolEntityCohorts.nonEmpty then
                  Some("elastic pools cannot use authored cohorts (scaling clears them)")
                else
                  AutoscaleBand.validate(
                    req.minNodes,
                    req.maxNodes,
                    st.distribution,
                    st.distribution.total,
                    autoscaleHardCap
                  )
        bandErr match
          case Some(msg) =>
            IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid_band", msg))))
          case None =>
            sup.setPoolAutoscale(key, req.minNodes.zip(req.maxNodes)).map {
              case Left(err: SupervisorError.NotFound) =>
                Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
              case Left(err) =>
                Left((StatusCode.Conflict, ErrorResponse("update_failed", err.message)))
              case Right(_) =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.PoolSetAutoscale,
                  "ok",
                  tenant = Some(req.tenant),
                  target = Some(key.toString),
                  detail = Map(
                    "band" -> req.minNodes
                      .zip(req.maxNodes)
                      .map((mn, mx) => s"[$mn, $mx]")
                      .getOrElse("cleared")
                  )
                )
                respond(key, Map.empty) match
                  case Some(r) => Right(r)
                  case None    =>
                    Left(
                      (
                        StatusCode.NotFound,
                        ErrorResponse("not_found", s"pool $key disappeared after update")
                      )
                    )
            }

object PoolHandlers:

  /** The `status` `/api/pool/list` reports for a pool, shared with `/api/profile/pools` so both
    * routes speak the same values: `disabled` for a disabled pool, else `ready`.
    */
  def statusOf(p: PoolState): String = if p.disabled then "disabled" else "ready"
