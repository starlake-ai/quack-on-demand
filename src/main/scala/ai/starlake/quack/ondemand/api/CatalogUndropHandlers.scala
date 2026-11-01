package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{PoolKey, TenantDb}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

import scala.concurrent.duration.DurationInt

/** Undrop (Spec 03): list recoverable dropped tables and recreate one at its last-live snapshot.
  *
  * Discovery is a catalog read (`DuckLakeCatalogReader.listDroppedTables`); recovery is a
  * `CREATE TABLE ... AS SELECT * FROM t AT (VERSION => n)` routed through the same executor shape
  * as [[CatalogPreviewHandlers]] but wired with `recordExecution = true` in Main, so the recovery
  * snapshot carries the principal's author stamp. The engine primitive (time-travel-reading a
  * DROPPED table by name) was verified live 2026-07-14 on DuckDB 1.5.4 / DuckLake 0.3 and
  * re-verified on 1.5.5 (2026-07-25, DuckLakeCatalogReaderDroppedSpec); see the undrop design doc.
  *
  * `recoverable` audits under the `cfg.auditCatalogReads` knob like the other catalog reads;
  * `undrop` is a mutation and audits unconditionally (never row contents; detail carries schema,
  * table, restoredAs, fromSnapshot).
  */
final class CatalogUndropHandlers(
    sup: PoolSupervisor,
    executor: CatalogPreviewHandlers.PreviewExecutor,
    resolveReader: (String, String) => DuckLakeCatalogReader,
    cfg: CatalogConfig,
    callerOf: RestCaller,
    audit: AuditRecorder = AuditRecorder.noop,
    /** `UNDROP TABLE`'s statement leg: the caller's own session effective set, claims, JWT roles
      * and groups included, so the CTAS is decided exactly like a statement they typed (review I3).
      */
    sessionExecutor: CatalogUndropHandlers.SessionExecutor = CatalogUndropHandlers.unwiredSession
):
  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]
  // (pool, the CTAS, the commit message its write is stamped with: see [[recover]]).
  private type Run =
    (PoolKey, String, String) => IO[Either[RouterFailure, ai.starlake.quack.edge.QueryResult]]

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** Same unsafe-char screen as MaintenanceHandlers' scope fields: these values end up inside a SQL
    * text on the node session, so quotes, separators, and whitespace are refused outright.
    */
  private def hasUnsafeChars(s: String): Boolean =
    s.exists(c => c == '\'' || c == '"' || c == ';' || c == '\\' || c.isWhitespace || c.isControl)

  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    TenantDbGate(
      sup,
      rawTenant,
      tenantDb,
      apiKey,
      requireDuckLake = Some("undrop requires a ducklake tenant-db")
    )(scopeOf)

  /** Pool pick preferring a WRITE-eligible node (a CTAS needs WriteOnly/Dual to route); delegates
    * to [[PoolPicks.writePoolKey]].
    */
  private def writePoolKey(tenant: String, tenantDb: String): Option[PoolKey] =
    PoolPicks.writePoolKey(sup, tenant, tenantDb)

  def recoverable(tenant: String, tenantDb: String, limit: Option[Int], apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[RecoverableListResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf).flatMap { (tid, db) =>
      // A PAT's databases axis (review I1): an admin's token scoped elsewhere lists nothing here.
      tokenPrincipal(apiKey) match
        case Some(caller) if !caller.restriction.allowsDatabase(db) => Left(databaseRefused(db))
        case _                                                      => Right((tid, db))
    } match
      case Left(e) =>
        if cfg.auditCatalogReads then
          audit.rest(apiKey, "control-plane", AuditActions.CatalogRecoverableRead, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        val effectiveLimit = limit.map(_.max(1)).getOrElse(50).min(200)
        IO.blocking {
          val entries = resolveReader(tid, db).listDroppedTables(effectiveLimit).map(entryOf)
          if cfg.auditCatalogReads then
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.CatalogRecoverableRead,
              "ok",
              tenant = Some(tid),
              target = Some(db),
              detail = Map("count" -> entries.length.toString)
            )
          Right(RecoverableListResponse(entries))
        }

  private def entryOf(e: ai.starlake.quack.ondemand.catalog.DroppedTableEntry) =
    RecoverableTableEntry(
      e.schema,
      e.table,
      e.droppedAtSnapshot,
      e.lastLiveSnapshot,
      e.droppedAt,
      e.recoverable,
      e.tableId
    )

  /** The executor caller a bearer resolves to, when it resolves: its token restriction is what the
    * PAT axes below read (a session's is unrestricted).
    */
  private def tokenPrincipal(apiKey: Option[String]): Option[ExecCaller] =
    callerOf.resolve(apiKey).toOption.map(RestCaller.callerOf("undrop-scope", _))

  private def databaseRefused(db: String): (StatusCode, ErrorResponse) =
    (
      StatusCode.Forbidden,
      ErrorResponse("token_scope", s"database '$db' is not permitted for this token")
    )

  /** A token's scope on one recovery (review I1): [[TokenScope.refusal]], a recovery creating a
    * table on `pool`.
    */
  private def tokenRefusal(
      tid: String,
      caller: ExecCaller,
      db: String,
      pool: PoolKey
  ): Option[(StatusCode, ErrorResponse)] =
    TokenScope.refusal(sup, tid, caller, db, Some(pool), writes = true)

  def undrop(req: UndropRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[UndropResponse] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogUndrop, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        lazy val pool                                          = writePoolKey(tid, db)
        lazy val run: Either[(StatusCode, ErrorResponse), Run] =
          callerOf(s"undrop-$tid-$db", apiKey).flatMap { caller =>
            pool
              .flatMap(tokenRefusal(tid, caller, db, _))
              .toLeft((k: PoolKey, sql: String, message: String) =>
                executor(caller.copy(commitMessage = Some(message)), k, sql)
              )
          }
        recover(
          tid,
          db,
          req.schema,
          req.table,
          req.asName,
          req.fromSnapshot,
          req.tableId,
          pool,
          run
        ).map { outcome =>
          auditUndrop(outcome, tid, db, req.schema, req.table) { (tag, target, detail) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.CatalogUndrop,
              tag,
              tenant = Some(tid),
              target = Some(target),
              detail = detail
            )
          }
          outcome
        }

  // ---------- self-service (the profile surface; MCP for a non-admin) ----------

  /** The caller's own tenant and its DuckLake tenant-db `tenantDb`: the self-service routes name no
    * tenant, so a regular user can only ever reach their own. A PAT's databases axis applies.
    */
  private def mine(
      tenantDb: String,
      apiKey: Option[String]
  ): Either[(StatusCode, ErrorResponse), (RestCaller.Principal, String, TenantDb)] =
    callerOf.resolve(apiKey).flatMap { principal =>
      val own = principal match
        case RestCaller.Principal.Session(s) => s.profile.tenant
        case RestCaller.Principal.Pat(p)     => p.user.tenant
      val restriction = RestCaller.callerOf("undrop-scope", principal).restriction
      own.flatMap(TenantDbGate.resolveTenantId(sup, _)) match
        case None =>
          err(
            StatusCode.BadRequest,
            "tenant_required",
            "a superuser has no tenant of its own: use the catalog undrop endpoints"
          )
        case Some(_) if !restriction.allowsDatabase(tenantDb) => Left(databaseRefused(tenantDb))
        case Some(tid)                                        =>
          sup.findTenantDb(tid, tenantDb) match
            case None => err(StatusCode.NotFound, "not_found", s"database '$tenantDb' not found")
            case Some(td) if td.kind != ai.starlake.quack.model.TenantDbKind.DuckLake =>
              err(StatusCode.BadRequest, "invalid_kind", "undrop requires a ducklake database")
            case Some(td) => Right((principal, tid, td))
    }

  /** The first pool of the database the caller may connect to (write-capable first; a token's pools
    * axis applies), with the effective set its handshake decides, narrowed by a token's
    * restriction. The authorization service unavailable on a pool before any admits the caller:
    * 503, as the profile's pool listing answers (review M3).
    */
  private def reachable(
      caller: ExecCaller,
      tid: String,
      db: String
  ): Either[(StatusCode, ErrorResponse), (PoolKey, EffectiveSet)] =
    val writable = PoolPicks.writePoolKey(sup, tid, db)
    val decided  = sup
      .list()
      .map(_.key)
      .filter(k => k.tenant == tid && k.tenantDb == db)
      .filter(k => caller.restriction.allowsPool(k.pool))
      .sortBy(k => (!writable.contains(k), k.pool))
      .to(LazyList)
      .map(k => k -> sup.authorizeHandshakeDetailed(caller.authzRequest(k)))
    decided.collectFirst {
      case (k, Right(a)) =>
        Right(
          k -> ai.starlake.quack.ondemand.rbac.Attenuation
            .attenuatedBy(a.effectiveSet, caller.restriction)
        )
      case (_, Left(ai.starlake.quack.ondemand.rbac.HandshakeDenial.Unavailable(msg))) =>
        Left(
          (
            StatusCode.ServiceUnavailable,
            ErrorResponse("authz_unavailable", s"authorization service unavailable: $msg")
          )
        )
    } match
      case Some(result) => result
      case None         =>
        err(
          StatusCode.Forbidden,
          "no_pool_access",
          s"you may connect to no pool of database '$db'"
        )

  /** Whether `eff`'s grants cover a read of `schema.table` in `td` (rule 1 of dropped tables: the
    * last live name, which the listing gives, judged like a read of that name). QoD grants only: an
    * OPA tenant's listing is narrowed by them too, as the filtered metadata is.
    */
  private def readable(
      eff: EffectiveSet,
      tid: String,
      td: TenantDb,
      schema: String,
      table: String
  ) =
    val alias    = TenantDb.catalogAlias(sup.effectiveMetastoreFor(tid, td.name), td.name)
    val catalogs = sup.listTenantDbsByTenant(tid).map(_.name).toSet + alias
    ai.starlake.quack.edge.sql.GrantMatch.covers(
      eff.permissions,
      catalogs,
      alias,
      schema,
      table,
      ai.starlake.acl.parser.Verb.Read
    )

  /** How many dropped tables the self-service listing reads before its grant filter: the limit is
    * applied after the filter (review M4), over at most this many.
    */
  private val MineScan = 1000

  /** `GET /api/profile/recoverable`: the dropped tables of the caller's own database that they may
    * recover: their name is free of any live table or view (the listing leaves those out, as the
    * node refuses them) and their grants cover a read of it. Listed under their last live name.
    */
  def recoverableMine(
      tenantDb: String,
      limit: Option[Int],
      apiKey: Option[String]
  ): Out[RecoverableListResponse] =
    IO.blocking {
      for
        (principal, tid, td) <- mine(tenantDb, apiKey)
        caller = RestCaller.callerOf(s"undrop-$tid-${td.name}", principal)
        (_, eff) <- reachable(caller, tid, td.name)
      yield
        val effectiveLimit = limit.map(_.max(1)).getOrElse(50).min(200)
        val entries        = resolveReader(tid, td.name)
          .listDroppedTables(MineScan)
          .filter(e => readable(eff, tid, td, e.schema, e.table))
          .take(effectiveLimit)
          .map(entryOf)
        RecoverableListResponse(entries)
    }

  /** `POST /api/profile/undrop`: the same recovery as [[undrop]], in the caller's own tenant, as
    * them, on a pool they may connect to, within every axis of their token.
    */
  def undropMine(req: ProfileUndropRequest, apiKey: Option[String]): Out[UndropResponse] =
    IO.blocking(
      for
        (principal, tid, td) <- mine(req.tenantDb, apiKey)
        caller = RestCaller.callerOf(s"undrop-$tid-${td.name}", principal)
        (poolKey, _) <- reachable(caller, tid, td.name)
        _            <- tokenRefusal(tid, caller, td.name, poolKey).toLeft(())
      yield (tid, td, caller, poolKey)
    ).flatMap {
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogUndrop, "denied")
        IO.pure(Left(e))
      case Right((tid, td, caller, poolKey)) =>
        recover(
          tid,
          td.name,
          req.schema,
          req.table,
          req.asName,
          None,
          req.tableId,
          Some(poolKey),
          Right((k: PoolKey, sql: String, message: String) =>
            executor(caller.copy(commitMessage = Some(message)), k, sql)
          )
        ).map { outcome =>
          auditUndrop(outcome, tid, td.name, req.schema, req.table) { (tag, target, detail) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.CatalogUndrop,
              tag,
              tenant = Some(tid),
              target = Some(target),
              detail = detail
            )
          }
          outcome
        }
    }

  /** `UNDROP TABLE [schema.]t [AS new_name]` (the gateway's admin dialect), on the caller's own
    * session: the same recovery as [[undrop]], its CTAS run through [[sessionExecutor]] with the
    * session's own effective set (claims, JWT roles and groups included, review I3), so the node
    * decides it exactly like a statement they typed: the read of the dropped table (its last live
    * name's grant) and the create (DDL in the schema). On a connection of its own (the session's id
    * with `/undrop`), never inside the client's transaction (the dialect refuses UNDROP there).
    * `schema` defaults to the database's default schema; names compare as DuckDB resolves them,
    * without case. The answer is the dialect's (status, detail) row; a refusal is a plain-words
    * [[RouterFailure]].
    */
  def undropStatement(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      eff: Option[EffectiveSet],
      schema: Option[String],
      table: String,
      asName: Option[String]
  ): IO[Either[RouterFailure, ai.starlake.quack.edge.QueryResult]] =
    eff match
      case None => IO.pure(Left(RouterFailure.AccessDenied("UNDROP TABLE needs a signed-in user")))
      case Some(e) =>
        val tid = TenantDbGate.resolveTenantId(sup, poolKey.tenant).getOrElse(poolKey.tenant)
        sup.findTenantDb(tid, poolKey.tenantDb) match
          case None =>
            IO.pure(Left(RouterFailure.NotFound(s"database '${poolKey.tenantDb}' not found")))
          case Some(td) if td.kind != ai.starlake.quack.model.TenantDbKind.DuckLake =>
            IO.pure(
              Left(RouterFailure.BadRequest("UNDROP TABLE needs a DuckLake database"))
            )
          case Some(td) =>
            val s = schema.getOrElse(
              sup.effectiveMetastoreFor(tid, td.name).getOrElse("schemaName", "main")
            )
            val run: Run =
              (k, sql, message) =>
                sessionExecutor(s"$connectionId/undrop", user, k, sql, e, message)
            recover(tid, td.name, s, table, asName, None, None, Some(poolKey), Right(run)).map {
              outcome =>
                auditUndrop(outcome, tid, td.name, s, table) { (outcomeTag, target, detail) =>
                  audit.restAs(
                    user,
                    if e.user.tenant.isEmpty then "system" else "tenant",
                    "control-plane",
                    AuditActions.CatalogUndrop,
                    outcomeTag,
                    tenant = Some(tid),
                    target = Some(target),
                    detail = detail
                  )
                }
                outcome match
                  case Right(r) =>
                    Right(
                      ai.starlake.quack.edge.admin.AdminResults.ok(
                        s"table ${r.schema}.${r.table} restored as ${r.schema}.${r.restoredAs} " +
                          s"from snapshot ${r.fromSnapshot}"
                      )
                    )
                  case Left((code, err)) => Left(statementFailure(code, err, s, table, asName))
            }

  /** A refusal of the recovery in plain words, for the admin dialect. */
  private def statementFailure(
      code: StatusCode,
      err: ErrorResponse,
      schema: String,
      table: String,
      asName: Option[String]
  ): RouterFailure =
    err.error match
      case "not_found" =>
        RouterFailure.NotFound(s"there is no dropped table $schema.$table")
      case "name_conflict" =>
        val target = asName.getOrElse(table)
        RouterFailure.AlreadyExists(
          s"a table or view $schema.$target exists; use UNDROP TABLE $schema.$table AS <new name>"
        )
      case "acl_denied" =>
        RouterFailure.AccessDenied(s"not permitted: ${err.message}")
      case "invalid_name" =>
        RouterFailure.BadRequest(
          "UNDROP TABLE names must be plain names, free of quotes, separators and whitespace"
        )
      case _ if code == StatusCode.Gone =>
        RouterFailure.NotFound(s"$schema.$table is no longer recoverable: ${err.message}")
      case _ => RouterFailure.Internal(err.message)

  /** Audits one recovery: ok with its detail, or denied. */
  private def auditUndrop(
      outcome: Either[(StatusCode, ErrorResponse), UndropResponse],
      tid: String,
      db: String,
      schema: String,
      table: String
  )(write: (String, String, Map[String, String]) => Unit): Unit =
    outcome match
      case Right(r) =>
        write(
          "ok",
          s"$db/$schema.$table",
          Map(
            "schema"       -> r.schema,
            "table"        -> r.table,
            "restoredAs"   -> r.restoredAs,
            "fromSnapshot" -> r.fromSnapshot.toString
          )
        )
      case Left(_) => write("denied", s"$db/$schema.$table", Map.empty)

  /** The recovery itself, shared by the REST endpoint, the MCP tool and `UNDROP TABLE`: name
    * screen, the dropped table's last live snapshot (looked up without case, as DuckDB resolves
    * names: the CTAS then names the table as stored), the target name's check (no live table or
    * view holds it, without case), then `CREATE TABLE <target> AS SELECT * FROM <table> AT (VERSION
    * => n)` through `run` on `pool`, its commit stamped with a message unique to this run, which
    * alone settles an answer that was lost. `pool` and `run` are consulted only once the catalog
    * checks pass, in that order. No audit: the callers audit.
    */
  def recover(
      tid: String,
      db: String,
      schema: String,
      table: String,
      asName: Option[String],
      fromSnapshotReq: Option[Long],
      tableIdReq: Option[Long],
      pool: => Option[PoolKey],
      run: => Either[(StatusCode, ErrorResponse), Run]
  ): Out[UndropResponse] =
    if List(Some(schema), Some(table), asName).flatten.exists(v => v.isEmpty || hasUnsafeChars(v))
    then
      IO.pure(
        err(
          StatusCode.BadRequest,
          "invalid_name",
          "schema, table, and asName must be non-empty and free of quotes, separators, " +
            "and whitespace"
        )
      )
    else
      val reader = resolveReader(tid, db)
      // A listed entry names its drop by table id: a name dropped twice is two tables, and the
      // name alone resolves to the newest.
      tableIdReq.fold(reader.findDroppedTable(schema, table))(
        reader.findDroppedTableById(schema, table, _)
      ) match
        case None =>
          val which = tableIdReq.fold("")(id => s" with table id $id")
          IO.pure(
            err(StatusCode.NotFound, "not_found", s"no dropped table '$schema.$table'$which")
          )
        case Some(entry) =>
          val target       = asName.getOrElse(entry.table)
          val fromSnapshot = fromSnapshotReq.getOrElse(entry.lastLiveSnapshot)
          if fromSnapshotReq.exists(_ > entry.lastLiveSnapshot) then
            IO.pure(
              err(
                StatusCode.UnprocessableEntity,
                "invalid_snapshot",
                s"fromSnapshot must be at most the last-live snapshot " +
                  s"${entry.lastLiveSnapshot} (the table does not resolve at later versions)"
              )
            )
          else if !reader.snapshotExists(fromSnapshot) then
            IO.pure(
              err(
                StatusCode.Gone,
                "snapshot_expired",
                s"snapshot $fromSnapshot has been expired; the table is no longer recoverable"
              )
            )
          else if reader.maxSnapshotId().exists(m => reader.nameHeldAt(entry.schema, target, m))
          then
            IO.pure(
              err(
                StatusCode.Conflict,
                "name_conflict",
                s"table or view '${entry.schema}.$target' exists; supply asName to restore " +
                  "under a different name"
              )
            )
          else
            pool match
              case None =>
                IO.pure(
                  err(
                    StatusCode.NotFound,
                    "no_pool",
                    s"tenant-db '$db' has no running pool; undrop needs at least one pool " +
                      "with a live WriteOnly/Dual node"
                  )
                )
              case Some(poolKey) =>
                run match
                  case Left(e)        => IO.pure(Left(e))
                  case Right(execute) =>
                    val sql =
                      s"CREATE TABLE ${quoteIdent(entry.schema)}.${quoteIdent(target)} AS " +
                        s"SELECT * FROM ${quoteIdent(entry.schema)}.${quoteIdent(entry.table)} " +
                        s"AT (VERSION => $fromSnapshot)"
                    val committed: Either[(StatusCode, ErrorResponse), UndropResponse] =
                      Right(UndropResponse(entry.schema, entry.table, target, fromSnapshot))
                    // Unique to this run: the router stamps the CTAS's commit with it in place of
                    // `flightsql create` (its own prelude, never client SQL, so no policy screen
                    // sees a commit-message call). A failure that may hide a commit is settled by
                    // this commit alone, never by whatever table holds the name now: another
                    // writer's CREATE, or another undrop's, would hold it too.
                    val message =
                      s"undrop ${entry.schema}.${entry.table} as ${entry.schema}.$target " +
                        s"from snapshot $fromSnapshot [${java.util.UUID.randomUUID()}]"
                    // `lost`: the answer cannot tell whether the CTAS committed (a timeout, a
                    // broken stream). A definite failure keeps its own words unless the catalog
                    // shows this commit after all.
                    def settled(
                        reason: String,
                        lost: Boolean
                    ): Either[(StatusCode, ErrorResponse), UndropResponse] =
                      scala.util.Try(reader.snapshotByCommitMessage(message)) match
                        case scala.util.Success(Some(_)) => committed
                        case scala.util.Success(None)
                            if lost && scala.util
                              .Try(
                                reader
                                  .maxSnapshotId()
                                  .exists(m => reader.tableExistsAt(entry.schema, target, m))
                              )
                              .getOrElse(false) =>
                          err(
                            StatusCode.BadGateway,
                            "undrop_outcome_unknown",
                            s"$reason; a table now holds ${entry.schema}.$target, but the " +
                              "catalog shows no commit by this undrop (another writer may have " +
                              "created it, or writes are not stamped: QOD_STAMP_WRITES); check " +
                              "it before retrying"
                          )
                        case scala.util.Success(None) =>
                          err(StatusCode.BadGateway, "undrop_failed", reason)
                        case scala.util.Failure(t) if lost =>
                          err(
                            StatusCode.BadGateway,
                            "undrop_outcome_unknown",
                            s"$reason; the catalog could not be read to settle it " +
                              s"(${t.getMessage}): check ${entry.schema}.$target before retrying"
                          )
                        case scala.util.Failure(_) =>
                          err(StatusCode.BadGateway, "undrop_failed", reason)
                    execute(poolKey, sql, message)
                      .timeout(cfg.undropTimeoutSec.seconds)
                      .attempt
                      .map {
                        case Left(e) =>
                          // Cancellation of the blocking HTTP leg is best-effort, so a CTAS that
                          // outlives the timeout may still have committed on the node.
                          val reason = e match
                            case _: java.util.concurrent.TimeoutException =>
                              s"undrop timed out after ${cfg.undropTimeoutSec}s"
                            case other => s"undrop failed: ${other.getMessage}"
                          settled(reason, lost = true)
                        case Right(Left(RouterFailure.AccessDenied(reason))) =>
                          err(StatusCode.Forbidden, "acl_denied", reason)
                        case Right(Left(failure)) =>
                          // The router's own "outcome is unknown" included: its words say so.
                          settled(failure.reason, lost = false)
                        case Right(Right(result)) =>
                          // Read to its end before answering: a stamped CTAS commits when its
                          // stream is exhausted, and a refused COMMIT fails it there (closing an
                          // undrained one would only log it). A broken stream after the COMMIT
                          // went out is settled from the catalog, as a timeout is.
                          WriteResults.drain(result) match
                            case Right(_)  => committed
                            case Left(why) => settled(s"undrop failed: $why", lost = true)
                      }

object CatalogUndropHandlers:

  /** `(connectionId, user, poolKey, sql, the session's effective set, the commit message its write
    * is stamped with)`: a statement run as that session (Main binds
    * FlightSqlRouter.executeOneShot).
    */
  type SessionExecutor = (
      String,
      String,
      PoolKey,
      String,
      EffectiveSet,
      String
  ) => IO[Either[RouterFailure, ai.starlake.quack.edge.QueryResult]]

  val unwiredSession: SessionExecutor =
    (_, _, _, _, _, _) =>
      IO.pure(Left(RouterFailure.Internal("UNDROP TABLE's session executor is not wired")))
