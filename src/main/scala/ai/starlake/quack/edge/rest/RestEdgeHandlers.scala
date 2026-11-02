package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.{Names, PoolKey, SessionCatalog, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  BoundedWait,
  CatalogPreviewHandlers,
  ErrorResponse,
  ExecCaller,
  PoolPicks,
  SnapshotSelector
}
import ai.starlake.quack.ondemand.auth.PatPrincipal
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import cats.data.EitherT
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import org.apache.arrow.vector.ipc.ArrowReader
import sttp.model.{Header, StatusCode}

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
object RestEdgeHandlers:

  type Failure = RestResponses.Failure
  type Out     = IO[Either[Failure, RestOk]]

  private val SystemSchemas = Set("information_schema", "pg_catalog")

  /** The four routes: their template (for access logs, never the concrete path) and the parameters
    * each admits. `None` admits the whole `/rows` vocabulary; the listings and the detail refuse
    * anything else rather than ignore it.
    */
  enum Route(val template: String, val params: Option[Set[String]]):
    case Schemas
        extends Route(
          "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas",
          Some(Set("pool", "format"))
        )
    case Tables
        extends Route(
          "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables",
          Some(Set("pool", "format"))
        )
    case Table
        extends Route(
          "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}",
          Some(Set("pool", "format", "asOf", "asOfTag", "asOfTs"))
        )
    case Rows
        extends Route(
          "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/rows",
          None
        )

/** The REST edge's request lifecycle after the HTTP shell: authenticate the PAT, resolve the target
  * with MCP `run_sql`'s rules, pick the snapshot with the preview endpoint's rules, probe the
  * schema, parse and render ONE SELECT, execute it and encode the result.
  *
  * The edge is a thin translator. It holds no policy: grants, column and row policies, the pools
  * axis inside the executor, `branchOnly` and the token's timeout all run in the injected
  * `executor`, which is `Main.routedExecutor` in production and the seam a spec stubs. Every
  * statement is text from [[RestSql]] and goes through that executor exactly as MCP `run_sql` does,
  * so the validator and the rewriters see an ordinary SELECT.
  *
  * Two executors, both the routed executor: `executor` records its statements (history, metering,
  * audit) and runs every statement but one; `probeExecutor` does not record and runs only the
  * schema probe, which is plumbing rather than the caller's statement, as FlightSQL's prepare-time
  * probe is. A consequence for audit: a request refused AT the probe (an ungranted table, a deny
  * column policy over `SELECT *`) leaves no data-denial row; the client sees the same 404 as for a
  * missing table and the access log keeps the request. Denials of the recorded statements (the
  * listings, the data statement) are audited as usual.
  *
  * Views: DuckLake's behaviour on `AT (VERSION => n)` over a view is not established, so a name
  * that is or was a view is read at the current state with no snapshot pin, no `X-QoD-Snapshot` and
  * no caching, and `asOf*` on it is a 400 `invalid_selector`. That answer comes after the probe, so
  * an invisible view is still the plain 404.
  *
  * Messages never carry a parameter value, node text never reaches a body or a WARN line (only the
  * DEBUG level holds it) and every denial of an object is the same 404 as its absence.
  */
final class RestEdgeHandlers(
    cfg: RestEdgeConfig,
    sup: PoolSupervisor,
    resolvePat: String => Option[PatPrincipal],
    executor: CatalogPreviewHandlers.PreviewExecutor,
    probeExecutor: CatalogPreviewHandlers.PreviewExecutor,
    catalogReader: (String, String) => DuckLakeCatalogReader,
    tagSnapshot: (String, String, String) => Option[Long],
    /** Overrides `cfg.stmtTimeoutSec` (a test seam); a token's `stmtTimeoutMs` still lowers it. */
    stmtTimeout: Option[FiniteDuration] = None
) extends LazyLogging:

  import RestEdgeHandlers.*
  import RestResponses.*

  private type Step[A] = EitherT[IO, RestError, A]

  private def lift[A](e: Either[RestError, A]): Step[A] = EitherT.fromEither[IO](e)
  private def fail[A](e: RestError): Step[A]            = EitherT.leftT[IO, A](e)
  private def io[A](a: IO[A]): Step[A]                  = EitherT.liftF(a)

  /** The resolved target of one request. `cold`: the pool was suspended, or had no routable node,
    * when the request arrived, so its statements wait behind a resume.
    */
  private final case class Target(
      caller: ExecCaller,
      td: TenantDb,
      poolKey: PoolKey,
      catalog: String,
      cold: Boolean
  )

  // ---- the four routes -----------------------------------------------------------------------

  def schemas(tenant: String, tenantDb: String, req: RestRequest): Out =
    respond(Route.Schemas, req) {
      for
        p   <- authenticate(tenant, req)
        _   <- lift(segment(tenantDb))
        q   <- lift(parseQuery(Route.Schemas, req.rawQuery))
        fmt <- lift(RestFormat.negotiate(q.format, req.accept))
        t   <- resolveTarget(p, tenant, tenantDb, q.pool)
        cap = listingCap(t)
        qr    <- execute(t, RestSql.listSchemas(t.catalog, cap), hiddenFailure(req.requestId), req)
        found <- io(consume(qr)(r => readStrings(r.rows, cap)))
      yield listing(fmt, List("name"), found._1.map(r => List(Json.fromString(r(0)))), found._2)
    }

  def tables(tenant: String, tenantDb: String, schema: String, req: RestRequest): Out =
    respond(Route.Tables, req) {
      for
        p   <- authenticate(tenant, req)
        _   <- lift(segment(tenantDb))
        sch <- lift(schemaSegment(schema))
        q   <- lift(parseQuery(Route.Tables, req.rawQuery))
        fmt <- lift(RestFormat.negotiate(q.format, req.accept))
        t   <- resolveTarget(p, tenant, tenantDb, q.pool)
        cap = listingCap(t)
        sql = RestSql.listTables(t.catalog, sch, cap)
        qr    <- execute(t, sql, hiddenFailure(req.requestId), req)
        found <- io(consume(qr)(r => readStrings(r.rows, cap)))
        // Nothing visible in the schema is indistinguishable from no schema.
        _ <- if found._1.isEmpty then fail(RestError.NotFound) else lift(Right(()))
      yield listing(
        fmt,
        List("name", "type"),
        found._1.map(r => List(Json.fromString(r(0)), Json.fromString(objectType(r(1))))),
        found._2
      )
    }

  def table(tenant: String, tenantDb: String, schema: String, name: String, req: RestRequest): Out =
    respond(Route.Table, req) {
      for
        p    <- authenticate(tenant, req)
        _    <- lift(segment(tenantDb))
        sch  <- lift(schemaSegment(schema))
        tbl  <- lift(segment(name))
        q    <- lift(parseQuery(Route.Table, req.rawQuery))
        fmt  <- lift(RestFormat.negotiate(q.format, req.accept))
        t    <- resolveTarget(p, tenant, tenantDb, q.pool)
        view <- viewNamed(tenant, t.td, sch, tbl, req.requestId)
        snap <- if view then lift(Right(None)) else snapshotOf(tenant, t.td, q, req.requestId)
        at = RestSql.Target(t.catalog, sch, tbl, snap)
        pr   <- probe(t, at, req)
        _    <- if pr._1.isEmpty then fail(RestError.NotFound) else lift(Right(()))
        _    <- refuseViewTimeTravel(view, q)
        kind <- io(objectTypeOf(t, sch, tbl, req))
      yield
        val cols = pr._1.map(c => List(Json.fromString(c.name), Json.fromString(c.kind.sqlType)))
        val body = fmt match
          case RestFormat.Json =>
            Json
              .obj(
                "name"    -> Json.fromString(tbl),
                "type"    -> kind.fold(Json.Null)(Json.fromString),
                "columns" -> Json.arr(cols.map(c => Json.obj("name" -> c(0), "type" -> c(1)))*)
              )
              .noSpaces
          case RestFormat.Csv => csv(List("name", "type"), cols)
        RestOk(
          baseHeaders(fmt, pinned(t, q, snap)) ++
            snap.map(id => Header("X-QoD-Snapshot", id.toString)),
          BodyBytes.of(body)
        )
    }

  def rows(tenant: String, tenantDb: String, schema: String, name: String, req: RestRequest): Out =
    respond(Route.Rows, req) {
      for
        p    <- authenticate(tenant, req)
        _    <- lift(segment(tenantDb))
        sch  <- lift(schemaSegment(schema))
        tbl  <- lift(segment(name))
        q    <- lift(parseQuery(Route.Rows, req.rawQuery))
        f    <- lift(RestFormat.negotiate(q.format, req.accept))
        t    <- resolveTarget(p, tenant, tenantDb, q.pool)
        view <- viewNamed(tenant, t.td, sch, tbl, req.requestId)
        sn   <- if view then lift(Right(None)) else snapshotOf(tenant, t.td, q, req.requestId)
        at = RestSql.Target(t.catalog, sch, tbl, sn)
        pr <- probe(t, at, req)
        _  <- refuseViewTimeTravel(view, q)
        rq <- lift(RestResolver.resolve(q, pr._1))
        // min(limit ?: defaultLimit, maxRows, token maxRows); the statement fetches one more.
        limit = t.caller.effectiveMaxRows(cfg.maxRows, q.limit.getOrElse(cfg.defaultLimit))
        // Same node as the probe: a soft pin, a vanished node falls back as usual.
        pinnedCaller = t.caller.copy(preferredNode = Some(pr._2))
        sql          = RestSql.render(at, rq, limit)
        qr  <- execute(t.copy(caller = pinnedCaller), sql, dataFailure(req.requestId), req)
        enc <- io(
          consume(qr)(r => RestResultEncoder.encode(f, r.rows, limit, cfg.maxResponseBytes))
        )
      yield
        val range =
          if enc.rows == 0 then "*/*" else s"${q.offset}-${q.offset.toLong + enc.rows - 1}/*"
        // Truncated when the byte cap, or a server or token row cap (not the client's own limit),
        // cut the page.
        val truncated = enc.cut || (enc.more && q.limit.forall(_ > limit))
        RestOk(
          baseHeaders(f, pinned(t, q, sn)) ++
            sn.map(id => Header("X-QoD-Snapshot", id.toString)) ++
            List(Header("Content-Range", range)) ++
            Option.when(truncated)(Header("X-QoD-Truncated", "true")),
          enc.bytes
        )
    }

  // ---- step 2: authentication ----------------------------------------------------------------

  private def authenticate(tenant: String, req: RestRequest): Step[PatPrincipal] =
    for
      p <- EitherT(IO.blocking(RestAuth.authenticate(req.authorization, resolvePat)))
      _ <- lift(RestAuth.admit(p, tenant))
    yield p

  // ---- path segments and parameters ----------------------------------------------------------

  /** QoD's identifier rule: a segment that breaks it names no object, so it is a 404. */
  private def segment(s: String): Either[RestError, String] =
    if Names.isValid(s) then Right(s) else Left(RestError.NotFound)

  private def schemaSegment(s: String): Either[RestError, String] =
    segment(s).filterOrElse(
      x => !SystemSchemas.contains(RestResolver.asciiLower(x)),
      RestError.NotFound
    )

  private def parseQuery(route: Route, raw: String): Either[RestError, RestQuery] =
    for
      pairs <- RestQuery.decodeQueryString(raw)
      _     <- route.params.fold(Right(())) { allowed =>
        pairs
          .collectFirst {
            case (n, _) if !allowed.contains(n) =>
              RestError.invalidParameter(n, "not supported on this endpoint")
          }
          .toLeft(())
      }
      q <- RestQuery.parse(pairs)
    yield q

  // ---- step 3: target ------------------------------------------------------------------------

  /** MCP `run_sql`'s rules (`McpDataTools.poolKeyFor`): the database must exist, be on the token's
    * `databases` axis and not be a branch, all three failing alike with 404; a named pool must
    * serve that database; no pool means `PoolPicks.readPoolKey`. The `pools` axis is checked on the
    * RESOLVED key, as `McpDataTools.allowedPool` does ahead of the executor that enforces it again,
    * so an out-of-scope pool is a 403 `acl_denied` rather than the probe's catch-all 404.
    */
  private def resolveTarget(
      p: PatPrincipal,
      tenant: String,
      tenantDb: String,
      pool: Option[String]
  ): Step[Target] =
    lift(for
      td <- sup
        .findTenantDb(tenant, tenantDb)
        .filter(td => td.branchOf.isEmpty && p.restriction.allowsDatabase(td.name))
        .toRight(RestError.NotFound)
      key <- poolKeyFor(tenant, td.name, pool)
      _   <-
        if p.restriction.allowsPool(key.pool) then Right(())
        else Left(RestError.AclDenied("this pool is not permitted for this token"))
      state <- sup.get(key).toRight(RestError.PoolUnavailable)
      // The one session-catalog resolver the router validates against, fed the pool's
      // EFFECTIVE metastore; the edge never derives the catalog itself.
      catalog <- SessionCatalog
        .database(state.kindWire, state.metastore, state.defaultDatabase)
        .toRight(RestError.PoolUnavailable)
    yield Target(RestAuth.callerFor(p), td, key, catalog, state.suspended || !routable(key)))

  /** Whether the pool has a node the router may pick right now (the router's own resume test). */
  private def routable(key: PoolKey): Boolean =
    sup.snapshot(key).exists(snap => snap.nodes.exists(n => snap.loadOf(n.nodeId).routable))

  private def poolKeyFor(
      tenant: String,
      db: String,
      pool: Option[String]
  ): Either[RestError, PoolKey] =
    pool match
      case None       => PoolPicks.readPoolKey(sup, tenant, db).toRight(RestError.PoolUnavailable)
      case Some(name) =>
        sup
          .findPoolKeyByTenantAndPoolName(tenant, name)
          .filter(_.tenantDb == db)
          .toRight(RestError.NotFound)

  private def listingCap(t: Target): Int = t.caller.effectiveMaxRows(cfg.maxRows, cfg.maxRows)

  // ---- step 4: snapshot ----------------------------------------------------------------------

  /** The preview endpoint's rules: time travel needs DuckLake (`invalid_kind` otherwise); on
    * DuckLake the selector goes through `SnapshotSelector.resolve` and, with none, the latest
    * snapshot is pinned so the probe and the data statement read the same one.
    */
  private def snapshotOf(
      tenant: String,
      td: TenantDb,
      q: RestQuery,
      rid: String
  ): Step[Option[Long]] =
    if td.kind != TenantDbKind.DuckLake then
      if q.timeTravel then fail(RestError.InvalidKind) else lift(Right(None))
    else
      EitherT(
        IO.blocking {
          val reader = catalogReader(tenant, td.name)
          SnapshotSelector
            .resolve(
              q.asOf,
              q.asOfTag,
              q.asOfTs,
              maxId = () => reader.maxSnapshotId(),
              atOrBefore = (ts: Instant) => reader.snapshotAtOrBefore(ts),
              tagSnapshot = (t: String) => tagSnapshot(tenant, td.name, t),
              exists = (id: Long) => reader.snapshotExists(id)
            )
            .map {
              case SnapshotSelector.Resolution.Current   => reader.maxSnapshotId()
              case SnapshotSelector.Resolution.At(id, _) => Some(id)
            }
            .left
            .map(RestError.snapshot)
        }.handleError { t =>
          logger.warn(s"rest [$rid] snapshot lookup failed: ${t.getClass.getName}")
          Left(RestError.UpstreamError)
        }
      )

  /** Whether `schema.name` is, or was, a view of a DuckLake database (see the class Scaladoc on
    * views). Other kinds have no time travel, so the question does not arise there.
    */
  private def viewNamed(
      tenant: String,
      td: TenantDb,
      schema: String,
      name: String,
      rid: String
  ): Step[Boolean] =
    if td.kind != TenantDbKind.DuckLake then lift(Right(false))
    else
      EitherT(
        IO.blocking(Right(catalogReader(tenant, td.name).viewEverNamed(schema, name)))
          .handleError { t =>
            logger.warn(s"rest [$rid] view lookup failed: ${t.getClass.getName}")
            Left(RestError.UpstreamError)
          }
      )

  private def refuseViewTimeTravel(view: Boolean, q: RestQuery): Step[Unit] =
    if view && q.timeTravel then
      fail(RestError.InvalidSelector("time travel is not available on views"))
    else lift(Right(()))

  /** Immutable content: a DuckLake read pinned by `asOf` or `asOfTag` to a snapshot it was served
    * at.
    */
  private def pinned(t: Target, q: RestQuery, snapshot: Option[Long]): Boolean =
    t.td.kind == TenantDbKind.DuckLake && q.pinned && snapshot.isDefined

  // ---- steps 5 and 7: probe and execution ----------------------------------------------------

  /** The probe's columns (the column contract after policy) and the node that answered. Runs on the
    * unrecording executor (class Scaladoc).
    */
  private def probe(
      t: Target,
      at: RestSql.Target,
      req: RestRequest
  ): Step[(Vector[ProbedColumn], String)] =
    execute(t, RestSql.probe(at), hiddenFailure(req.requestId), req, probeExecutor).flatMap { qr =>
      io(consume(qr)(r => (ProbedColumn.fromArrow(r.rows.getVectorSchemaRoot.getSchema), r.nodeId)))
    }

  /** The table-or-view answer of the detail endpoint, from the tables listing. The probe cannot
    * tell the two apart; when the listing is denied (filtered metadata off, no grant on
    * `information_schema`) or cut by the cap, the type is unknown rather than guessed.
    */
  private def objectTypeOf(t: Target, schema: String, name: String, req: RestRequest) =
    val cap = listingCap(t)
    execute(t, RestSql.listTables(t.catalog, schema, cap), hiddenFailure(req.requestId), req).value
      .flatMap {
        case Left(_)   => IO.pure(None)
        case Right(qr) =>
          consume(qr)(r =>
            readStrings(r.rows, cap)._1.find(_(0) == name).map(r => objectType(r(1)))
          )
      }

  private def objectType(tableType: String): String =
    if tableType == "VIEW" then "view" else "table"

  /** One executor call under the edge's own bounded wait, `min(stmtTimeoutSec, PAT stmtTimeoutMs)`:
    * a result that arrives after the wait is closed, never dropped. Past the wait the answer is a
    * 504, unless the pool was cold when the request arrived: the wait then went to the resume, so
    * it is the retryable 503 `pool_resuming` the router's own hold would have given. A raised error
    * is an upstream failure whose text is logged at DEBUG only, never returned.
    */
  private def execute(
      t: Target,
      sql: String,
      onFailure: RouterFailure => RestError,
      req: RestRequest,
      via: CatalogPreviewHandlers.PreviewExecutor = executor
  ): Step[QueryResult] =
    val run: IO[Either[RestError, QueryResult]] =
      IO.defer(via(t.caller, t.poolKey, sql))
        .map(_.left.map(onFailure))
        .handleError { e =>
          logger.warn(s"rest [${req.requestId}] executor raised ${e.getClass.getName}")
          logger.debug(s"rest [${req.requestId}] executor raised: ${e.getMessage}")
          Left(RestError.UpstreamError)
        }
    val onTimeout = if t.cold then RestError.PoolResuming else RestError.StatementTimeout
    EitherT(BoundedWait.closingLate(run, timeoutFor(t.caller), onTimeout, _.close()))

  private def timeoutFor(caller: ExecCaller): FiniteDuration =
    val base = stmtTimeout.getOrElse(cfg.stmtTimeoutSec.seconds)
    caller.restriction.stmtTimeoutMs.filter(_ > 0).map(_.toLong.millis).fold(base)(_ min base)

  /** Reads a result under ONE idempotent finalizer that closes it (which also deregisters the
    * statement from the kill registry), on success, error and cancellation alike.
    */
  private def consume[A](qr: QueryResult)(f: QueryResult => A): IO[A] =
    val closed = new AtomicBoolean(false)
    IO.blocking(f(qr)).guarantee(IO.blocking(if closed.compareAndSet(false, true) then qr.close()))

  // ---- failure mapping -----------------------------------------------------------------------

  /** Probe and listings: a denial, a missing object and a bad request are all the same 404, so an
    * ungranted object cannot be told from an absent one.
    */
  private def hiddenFailure(rid: String)(f: RouterFailure): RestError = f match
    case RouterFailure.AccessDenied(_) | RouterFailure.NotFound(_) | RouterFailure.BadRequest(_) =>
      RestError.NotFound
    case other => common(rid, other)

  /** The data statement, after a probe that saw the object: a denial is an `acl_denied` whose
    * reason (it may name columns or tables) is logged, not returned.
    */
  private def dataFailure(rid: String)(f: RouterFailure): RestError = f match
    case RouterFailure.AccessDenied(reason) =>
      logger.info(s"rest [$rid] data statement denied")
      logger.debug(s"rest [$rid] data statement denied: $reason")
      RestError.AclDenied("access denied")
    case RouterFailure.NotFound(_) => RestError.NotFound
    case other                     => common(rid, other)

  private def common(rid: String, f: RouterFailure): RestError = f match
    // The router's hibernation hold expired, the same test MCP applies.
    case RouterFailure.Unavailable(r) if r.contains("resuming") => RestError.PoolResuming
    // routedExecutor's own bound on the token's stmtTimeoutMs (RoutedExecution).
    case RouterFailure.Unavailable(r) if r.startsWith("statement exceeded") =>
      RestError.StatementTimeout
    case RouterFailure.Unavailable(r) =>
      logger.warn(s"rest [$rid] pool unavailable")
      logger.debug(s"rest [$rid] pool unavailable: $r")
      RestError.PoolUnavailable
    case other =>
      logger.warn(s"rest [$rid] upstream failure: ${other.getClass.getSimpleName}")
      logger.debug(s"rest [$rid] upstream failure: ${other.reason}")
      RestError.UpstreamError

  /** Runs one route, turns its outcome into the boundary shape and writes the access log line: the
    * route TEMPLATE and the sanitised parameter NAMES, never a value, since filter values are often
    * personal data.
    */
  private def respond(route: Route, req: RestRequest)(step: Step[RestOk]): Out =
    step.value.attempt.map { outcome =>
      val result = outcome match
        case Right(Right(ok)) => Right(ok)
        case Right(Left(e))   => Left(failure(e, req.requestId))
        case Left(t)          =>
          logger.warn(s"rest [${req.requestId}] ${route.template} failed: ${t.getClass.getName}")
          Left(failure(RestError.UpstreamError, req.requestId))
      val status = result.fold(_._1.code, _ => 200)
      logger.info(
        s"rest [${req.requestId}] GET ${route.template} params=[${paramNames(req.rawQuery)}] " +
          s"-> $status"
      )
      result
    }
