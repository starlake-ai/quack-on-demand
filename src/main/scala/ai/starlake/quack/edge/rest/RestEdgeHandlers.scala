package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.{FlightSqlRouter, QueryResult, RouterFailure}
import ai.starlake.quack.model.{Names, PoolKey, TenantDb, TenantDbKind}
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
import cats.effect.{IO, Outcome}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import org.apache.arrow.vector.ipc.ArrowReader
import sttp.model.{Header, StatusCode}

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.*
object RestEdgeHandlers:

  type Failure = RestResponses.Failure
  type Out     = IO[Either[Failure, RestOk]]

  private val SystemSchemas = Set("information_schema", "pg_catalog")

  /** The longest schema or object name a path segment may carry. */
  private val MaxObjectName = 1024

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
  * probe is. A probe the router refuses is recorded once all the same (`recordRefusedProbe`, as
  * FlightSQL does since #211): history and a `data-denial` audit event, while the client sees the
  * same 404 as for a missing table.
  *
  * Nothing about an object is answered before the caller has seen it: a table or detail request
  * first probes the object at the current state, and only then looks at the view history, the kind
  * of database and the snapshot selector, whose own answers (400, 410, 422) would otherwise tell an
  * invisible object from an absent one. Until then every refusal is the 404, the target's own (no
  * such database or pool, a pool the token excludes) included.
  *
  * Snapshots: a read is pinned to a snapshot only when the client asks for one (`asOf*`); a read at
  * the current state carries no `AT` clause, so the time-travel guard of a policy holder never
  * judges it. Its `X-QoD-Snapshot` is the catalog's latest snapshot when that did not move while
  * the statement ran (read before and after), so it names the state the page was read at; under
  * concurrent writes it is left out.
  *
  * Views: DuckLake's behaviour on `AT (VERSION => n)` over a view is not established, so a name
  * that is or was a view carries no `X-QoD-Snapshot` and no caching, and `asOf*` on it is a 400
  * `invalid_selector`, answered after the probe.
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
    stmtTimeout: Option[FiniteDuration] = None,
    /** Records a probe the router refused (`FlightSqlRouter.recordRefusedProbe`); the probe runs on
      * the unrecording executor, so nothing else would.
      */
    recordRefusedProbe: (ExecCaller, PoolKey, String, RouterFailure) => Unit = (_, _, _, _) => (),
    /** The heap `/rows` pages may hold at once; [[RestEdgeServer]] gives a page's share back once
      * its response is written.
      */
    pageBudget: RestPageBudget = RestPageBudget.Unbounded
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

  /** An object the caller has seen ([[locate]]): what the data statement reads (`at`, pinned only
    * on the client's `asOf*`), the probe's columns there, the node that answered, and whether the
    * name is or was a view.
    */
  private final case class Located(
      at: RestSql.Target,
      columns: Vector[ProbedColumn],
      node: String,
      view: Boolean
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
        found <- execute(t, RestSql.listSchemas(t.catalog, cap), hiddenFailure(req.requestId), req)(
          reading(r => readStrings(r.rows, cap))
        )
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
        found <- execute(t, sql, hiddenFailure(req.requestId), req)(
          reading(r => readStrings(r.rows, cap))
        )
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
        p      <- authenticate(tenant, req)
        _      <- lift(segment(tenantDb))
        sch    <- lift(schemaSegment(schema))
        tbl    <- lift(objectSegment(name))
        q      <- lift(parseQuery(Route.Table, req.rawQuery))
        fmt    <- lift(RestFormat.negotiate(q.format, req.accept))
        t      <- resolveTarget(p, tenant, tenantDb, q.pool)
        before <- io(latestSnapshot(tenant, t.td, req.requestId))
        loc    <- locate(tenant, t, sch, tbl, q, req)
        after  <- io(latestSnapshot(tenant, t.td, req.requestId))
        _      <- if loc.columns.isEmpty then fail(RestError.NotFound) else lift(Right(()))
        kind   <- io(objectTypeOf(t, sch, tbl, req))
      yield
        val snap = shownSnapshot(loc, before, after)
        val cols =
          loc.columns.map(c => List(Json.fromString(c.name), Json.fromString(c.kind.sqlType)))
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
          baseHeaders(fmt, pinned(t, q, loc)) ++
            snap.map(id => Header("X-QoD-Snapshot", id.toString)),
          BodyBytes.of(body)
        )
    }

  def rows(tenant: String, tenantDb: String, schema: String, name: String, req: RestRequest): Out =
    respond(Route.Rows, req) {
      for
        p   <- authenticate(tenant, req)
        _   <- lift(segment(tenantDb))
        sch <- lift(schemaSegment(schema))
        tbl <- lift(objectSegment(name))
        q   <- lift(parseQuery(Route.Rows, req.rawQuery))
        f   <- lift(RestFormat.negotiate(q.format, req.accept))
        t   <- resolveTarget(p, tenant, tenantDb, q.pool)
        loc <- locate(tenant, t, sch, tbl, q, req)
        rq  <- lift(RestResolver.resolve(q, loc.columns))
        // min(limit ?: defaultLimit, maxRows, token maxRows); the statement fetches one more.
        limit = t.caller.effectiveMaxRows(cfg.maxRows, q.limit.getOrElse(cfg.defaultLimit))
        // Same node as the probe: a soft pin, a vanished node falls back as usual.
        pinnedCaller = t.caller.copy(preferredNode = Some(loc.node))
        sql          = RestSql.render(loc.at, rq, limit)
        room   <- io(pageBudget.reserve(req.requestId, timeoutFor(t.caller)))
        _      <- if room then lift(Right(())) else fail(RestError.Busy)
        before <- io(latestSnapshot(tenant, t.td, req.requestId))
        enc    <- execute(
          t.copy(caller = pinnedCaller),
          sql,
          dataFailure(req.requestId, filtered = rq.filters.nonEmpty),
          req
        )(reading(r => RestResultEncoder.encode(f, r.rows, limit, cfg.maxResponseBytes)))
        _     <- io(pageBudget.settle(req.requestId, enc.bytes.capacity.toLong))
        after <- io(latestSnapshot(tenant, t.td, req.requestId))
        sn = shownSnapshot(loc, before, after)
      yield
        val range =
          if enc.rows == 0 then "*/*" else s"${q.offset}-${q.offset.toLong + enc.rows - 1}/*"
        // Truncated when the byte cap, or a server or token row cap (not the client's own limit),
        // cut the page.
        val truncated = enc.cut || (enc.more && q.limit.forall(_ > limit))
        RestOk(
          baseHeaders(f, pinned(t, q, loc)) ++
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

  /** QoD's identifier rule, for the tenant-db: a segment that breaks it names no database, so it is
    * a 404.
    */
  private def segment(s: String): Either[RestError, String] =
    if Names.isValid(s) then Right(s) else Left(RestError.NotFound)

  /** A schema or object name: anything DuckDB can name (`"order-items"`, `"2024_sales"`), so every
    * object the tables listing names is reachable. Only an empty or over-long segment, or one
    * holding a control character, names nothing (404). The name reaches SQL only quoted
    * ([[RestSql]]) or as a bound parameter.
    */
  private def objectSegment(s: String): Either[RestError, String] =
    if s.nonEmpty && s.length <= MaxObjectName && !s.exists(Character.isISOControl) then Right(s)
    else Left(RestError.NotFound)

  private def schemaSegment(s: String): Either[RestError, String] =
    objectSegment(s).filterOrElse(
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
    * `databases` axis and not be a branch; a named pool must serve that database; no pool means
    * `PoolPicks.readPoolKey`. The `pools` axis is checked on the RESOLVED key, as
    * `McpDataTools.allowedPool` does ahead of the executor that enforces it again. Nothing here has
    * seen a grant yet, so every miss (no such database, no pool, a pool the token excludes, a pool
    * with no state) is the same 404 as an object the caller may not read.
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
      key   <- poolKeyFor(tenant, td.name, pool)
      _     <- Either.cond(p.restriction.allowsPool(key.pool), (), RestError.NotFound)
      state <- sup.get(key).toRight(RestError.NotFound)
      // The router's own session path (the USE prelude's catalog, which the policy steps qualify
      // unqualified names with): the edge never derives the catalog itself.
      catalog <- FlightSqlRouter
        .sessionPath(state)
        .map(_.catalog)
        .toRight(RestError.NotFound)
    yield Target(RestAuth.callerFor(p), td, key, catalog, state.suspended || !routable(key)))

  /** Whether the pool has a node the router may pick right now (the router's own resume test). */
  private def routable(key: PoolKey): Boolean =
    sup.snapshot(key).exists(snap => snap.nodes.exists(n => snap.loadOf(n.nodeId).routable))

  /** MCP `run_sql`'s own resolution ([[PoolPicks.forDatabase]]); a database with no pool, and a
    * pool not serving it, are as absent as one that does not exist.
    */
  private def poolKeyFor(
      tenant: String,
      db: String,
      pool: Option[String]
  ): Either[RestError, PoolKey] =
    PoolPicks.forDatabase(sup, tenant, db, pool).left.map(_ => RestError.NotFound)

  private def listingCap(t: Target): Int = t.caller.effectiveMaxRows(cfg.maxRows, cfg.maxRows)

  // ---- step 4: snapshot ----------------------------------------------------------------------

  /** The object, in the order that keeps every answer about it behind its grant: the probe at the
    * current state first (a missing, ungranted or deny-column object is the 404, and nothing else
    * is looked up), then the database's kind, the view history and the snapshot selector, and, for
    * `asOf*`, a second probe at that snapshot, whose columns are the ones the data statement reads.
    */
  private def locate(
      tenant: String,
      t: Target,
      sch: String,
      tbl: String,
      q: RestQuery,
      req: RestRequest
  ): Step[Located] =
    val current = RestSql.Target(t.catalog, sch, tbl, None)
    for
      now <- probe(t, current, req)
      _   <-
        if q.timeTravel && t.td.kind != TenantDbKind.DuckLake then fail(RestError.InvalidKind)
        else lift(Right(()))
      view <- viewNamed(tenant, t.td, sch, tbl, req.requestId)
      _    <- refuseViewTimeTravel(view, q)
      snap <- if q.timeTravel then snapshotOf(tenant, t.td, q, req.requestId) else lift(Right(None))
      at = current.copy(snapshot = snap)
      seen <- if snap.isEmpty then lift(Right(now)) else probe(t, at, req)
    yield Located(at, seen._1, seen._2, view)

  /** The snapshot a client's `asOf*` names, by the preview endpoint's rules
    * (`SnapshotSelector.resolve`), on DuckLake only (the caller checked the kind).
    */
  private def snapshotOf(
      tenant: String,
      td: TenantDb,
      q: RestQuery,
      rid: String
  ): Step[Option[Long]] =
    if td.kind != TenantDbKind.DuckLake then fail(RestError.InvalidKind)
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
              case SnapshotSelector.Resolution.Current   => None
              case SnapshotSelector.Resolution.At(id, _) => Some(id)
            }
            .left
            .map(RestError.snapshot)
        }.handleError { t =>
          logger.warn(s"rest [$rid] snapshot lookup failed: ${t.getClass.getName}")
          Left(RestError.UpstreamError)
        }
      )

  /** The DuckLake catalog's latest snapshot, read around a statement at the current state (see
    * [[shownSnapshot]]); None on another kind, or when the lookup fails (the header is then left
    * out, the read itself having answered).
    */
  private def latestSnapshot(tenant: String, td: TenantDb, rid: String): IO[Option[Long]] =
    if td.kind != TenantDbKind.DuckLake then IO.pure(None)
    else
      IO.blocking(catalogReader(tenant, td.name).maxSnapshotId()).handleError { t =>
        logger.debug(s"rest [$rid] latest snapshot lookup failed: ${t.getClass.getName}")
        None
      }

  /** The snapshot a page was read at: the one `asOf*` pinned, else, for a table, the latest one
    * when it did not move while the statement ran (`before`, `after`), so a client can send it back
    * as `asOf` for the next page. None for a view, or when a commit landed meanwhile.
    */
  private def shownSnapshot(
      loc: Located,
      before: Option[Long],
      after: Option[Long]
  ): Option[Long] =
    loc.at.snapshot.orElse(if loc.view then None else before.filter(b => after.contains(b)))

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
  private def pinned(t: Target, q: RestQuery, loc: Located): Boolean =
    t.td.kind == TenantDbKind.DuckLake && q.pinned && loc.at.snapshot.isDefined

  // ---- steps 5 and 7: probe and execution ----------------------------------------------------

  /** The probe's columns (the column contract after policy) and the node that answered. Runs on the
    * unrecording executor (class Scaladoc); a refusal is recorded once, then answered as the 404.
    */
  private def probe(
      t: Target,
      at: RestSql.Target,
      req: RestRequest
  ): Step[(Vector[ProbedColumn], String)] =
    val sql                                  = RestSql.probe(at)
    def refused(f: RouterFailure): RestError =
      try recordRefusedProbe(t.caller, t.poolKey, sql, f)
      catch
        case e: Exception =>
          logger.warn(s"rest [${req.requestId}] recording a refused probe failed: ${e.getClass}")
      hiddenFailure(req.requestId)(f)
    execute(t, sql, refused, req, probeExecutor)(
      reading(r => (ProbedColumn.fromArrow(r.rows.getVectorSchemaRoot.getSchema), r.nodeId))
    )

  /** The table-or-view answer of the detail endpoint, from `information_schema.tables`. The probe
    * cannot tell the two apart; when that lookup is denied (filtered metadata off, no grant on
    * `information_schema`), the type is unknown rather than guessed.
    */
  private def objectTypeOf(t: Target, schema: String, name: String, req: RestRequest) =
    execute(t, RestSql.tableType(t.catalog, schema, name), hiddenFailure(req.requestId), req)(
      reading(r => readStrings(r.rows, 1)._1.headOption.map(r => objectType(r(0))))
    ).value.map(_.toOption.flatten)

  private def objectType(tableType: String): String =
    if tableType == "VIEW" then "view" else "table"

  /** One executor call under the edge's own bounded wait, `min(stmtTimeoutSec, PAT stmtTimeoutMs)`:
    * a result that arrives after the wait is closed, never dropped. Past the wait the answer is a
    * 504, unless the pool was cold when the request arrived: the wait then went to the resume, so
    * it is the retryable 503 `pool_resuming` the router's own hold would have given. A raised error
    * is an upstream failure whose text is logged at DEBUG only, never returned.
    *
    * A result delivered in time is handed to `use`, which owns it from then on and must close it.
    * The hand-off has no cancellation point: a request cancelled after the result arrived but
    * before `use` ran (at the boundary of the wait), or while `use` runs, or a `use` that raises,
    * has the result closed here. Its close is idempotent, so a `use` that already closed it is not
    * harmed.
    */
  private def execute[A](
      t: Target,
      sql: String,
      onFailure: RouterFailure => RestError,
      req: RestRequest,
      via: CatalogPreviewHandlers.PreviewExecutor = executor
  )(use: QueryResult => IO[Either[RestError, A]]): Step[A] =
    val run: IO[Either[RestError, QueryResult]] =
      IO.defer(via(t.caller, t.poolKey, sql))
        .map {
          case Right(qr) => Right(closingOnce(qr))
          case Left(f)   => Left(onFailure(f))
        }
        .handleError { e =>
          logger.warn(s"rest [${req.requestId}] executor raised ${e.getClass.getName}")
          logger.debug(s"rest [${req.requestId}] executor raised: ${e.getMessage}")
          Left(RestError.UpstreamError)
        }
    val onTimeout = if t.cold then RestError.PoolResuming else RestError.StatementTimeout
    // The result as soon as it is delivered, before any cancellation point can lose it.
    val delivered = new AtomicReference[Option[QueryResult]](None)
    val waited    = BoundedWait.closingLate(
      run,
      timeoutFor(t.caller),
      onTimeout,
      _.close(),
      qr => IO(delivered.set(Some(qr)))
    )
    EitherT(IO.uncancelable { poll =>
      poll(waited)
        .onCancel(IO.defer(delivered.get.fold(IO.unit)(qr => IO.blocking(qr.close()))))
        .flatMap {
          case Left(e)   => IO.pure(Left(e))
          case Right(qr) =>
            poll(use(qr)).guaranteeCase {
              case Outcome.Succeeded(_) => IO.unit
              case _                    => IO.blocking(qr.close())
            }
        }
    })

  /** A `use` for [[execute]] that reads the result with `f` and closes it ([[consume]]). */
  private def reading[A](f: QueryResult => A): QueryResult => IO[Either[RestError, A]] =
    qr => consume(qr)(f).map(Right(_))

  /** `qr`, whose close runs at most once. */
  private def closingOnce(qr: QueryResult): QueryResult =
    val closed = new AtomicBoolean(false)
    qr.copy(close = () => if closed.compareAndSet(false, true) then qr.close())

  private def timeoutFor(caller: ExecCaller): FiniteDuration =
    val base = stmtTimeout.getOrElse(cfg.stmtTimeoutSec.seconds)
    caller.restriction.stmtTimeoutMs.filter(_ > 0).map(_.toLong.millis).fold(base)(_ min base)

  /** Reads a result under ONE finalizer that closes it (which also deregisters the statement from
    * the kill registry), on success, error and cancellation alike; the close is idempotent
    * ([[closingOnce]]).
    */
  private def consume[A](qr: QueryResult)(f: QueryResult => A): IO[A] =
    IO.blocking(f(qr)).guarantee(IO.blocking(qr.close()))

  // ---- failure mapping -----------------------------------------------------------------------

  /** Probe and listings: a denial, a missing object and a bad request are all the same 404, so an
    * ungranted object cannot be told from an absent one.
    */
  private def hiddenFailure(rid: String)(f: RouterFailure): RestError = f match
    case RouterFailure.AccessDenied(_) | RouterFailure.NotFound(_) | RouterFailure.BadRequest(_) =>
      RestError.NotFound
    case other => common(rid, other)

  /** The data statement, after a probe that saw the object: a denial is an `acl_denied` whose
    * reason (it may name columns or tables) is logged, not returned. A statement the node refuses
    * as invalid (`BadRequest`: a conversion error) while it carries filters is a filter value the
    * column's real type rejects, which the probe's Arrow type could not show (a UUID or JSON column
    * arrives as VARCHAR): a 400, not an upstream failure.
    */
  private def dataFailure(rid: String, filtered: Boolean)(f: RouterFailure): RestError = f match
    case RouterFailure.AccessDenied(reason) =>
      logger.info(s"rest [$rid] data statement denied")
      logger.debug(s"rest [$rid] data statement denied: $reason")
      RestError.AclDenied("access denied")
    case RouterFailure.NotFound(_)               => RestError.NotFound
    case RouterFailure.BadRequest(r) if filtered =>
      logger.info(s"rest [$rid] data statement refused by the node as invalid")
      logger.debug(s"rest [$rid] data statement refused by the node: $r")
      RestError.InvalidFilter("a filter value does not convert to its column's type")
    case other => common(rid, other)

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
