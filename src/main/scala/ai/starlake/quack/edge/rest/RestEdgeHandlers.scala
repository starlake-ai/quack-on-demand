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
import cats.effect.{IO, Outcome, Poll, Resource}
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

  /** The least a body Ember has not started yet is kept for before it is released, whatever the
    * token's statement timeout: writing the head can take a moment.
    */
  private val UnstartedFloor: FiniteDuration = 30.seconds

  /** A streamed format's encoder over one result: its bytes from the first one on, what it counted,
    * how to force it to stop, and the release of what it holds (never the result itself, which its
    * owner closes after).
    */
  private trait RowEncoder:
    def bytes: fs2.Stream[IO, Byte]
    def rows: Long
    def more: Boolean
    def cut: Boolean

    /** Makes whatever the encoder is blocked on return (a deadline, a cancel), without waiting. */
    def abort: IO[Unit]

    /** Releases what the encoder holds, once its reads have returned. */
    def close: IO[Unit]

  private object RowEncoder:
    /** Opens the encoder of `qr` in `format`, its first bytes in hand. Every wait on the node goes
      * through `poll` as a forcible read ([[ForcedReads]]), whose abort closes `qr` (its usual
      * close, the one an admin kill uses): a cancel, or a `poll` that bounds the wait, ends the
      * open promptly, with the encoder released.
      */
    def open(
        format: RestFormat,
        qr: QueryResult,
        limit: Int,
        maxBytes: Long,
        poll: Poll[IO]
    ): IO[RowEncoder] =
      val kill = IO.blocking(qr.close())
      format match
        case RestFormat.Parquet =>
          RestParquetWriter.opening(qr.rows, limit, maxBytes, kill, poll).map { w =>
            new RowEncoder:
              def bytes = fs2.Stream.chunk(fs2.Chunk.array(w.first)) ++ w.rest
              def rows  = w.rows
              def more  = w.more
              def cut   = w.cut
              def abort = w.abort
              def close = w.close()
          }
        case _ =>
          IO(new RestArrowWriter(qr.rows, limit, maxBytes)).flatMap { w =>
            val reads    = new ForcedReads(kill)
            val released = reads.settled *> IO.blocking(w.close())
            reads(poll)(w.start())
              .guaranteeCase {
                case Outcome.Succeeded(_) => IO.unit
                case _                    => released
              }
              .map { first =>
                new RowEncoder:
                  def bytes = fs2.Stream.chunk(fs2.Chunk.array(first)) ++
                    fs2.Stream
                      .repeatEval(IO.uncancelable(p => reads(p)(w.next())))
                      .unNoneTerminate
                      .flatMap(b => fs2.Stream.chunk(fs2.Chunk.array(b)))
                  def rows  = w.rows
                  def more  = w.more
                  def cut   = w.cut
                  def abort = reads.abort
                  def close = released
              }
          }

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

/** The REST edge's request lifecycle after the HTTP shell: authenticate the bearer (a PAT, or a JWT
  * of the tenant's own OIDC provider), resolve the target with MCP `run_sql`'s rules, pick the
  * snapshot with the preview endpoint's rules, probe the schema, parse and render ONE SELECT,
  * execute it and encode the result.
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
  * Two HTTP-layer resource controls wrap that pipeline without touching it: the failed-auth
  * throttle around step 2 ([[AuthThrottle]], keyed by the client address) and one in-flight slot
  * per request of its principal around steps 5 to 7 ([[UserLimiter]]).
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
    stmtTimeout: Option[FiniteDuration] = None,
    /** The failed-auth throttle, shared with [[RestEdgeServer]] in production. */
    throttle: Option[AuthThrottle] = None,
    /** The per-user in-flight cap. */
    limiter: Option[UserLimiter] = None,
    /** Bearer JWTs of the path tenant's own OIDC provider ([[RestOidc.resolve]]); PATs only when
      * left out.
      */
    resolveOidc: (String, String) => Option[RestPrincipal] = RestAuth.NoOidc,
    /** Whether `/rows` offers Parquet; the host probe in production, a seam in specs. */
    parquetAvailable: => Boolean = RestParquetWriter.available
) extends LazyLogging:

  private lazy val rowFormats: Set[RestFormat] =
    if parquetAvailable then RestFormat.Rows else RestFormat.Rows - RestFormat.Parquet

  // Built from `cfg` when not given: a default parameter cannot refer to another one here.
  private val authThrottle: AuthThrottle = throttle.getOrElse(AuthThrottle(cfg, _ => ()))
  private val userLimiter: UserLimiter   = limiter.getOrElse(UserLimiter(cfg))

  /** The Parquet slots: one per principal, `maxConcurrentParquet` for the whole edge. */
  private val parquetLimiter = new UserLimiter(perUser = 1, total = cfg.maxConcurrentParquet)

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
        found <- withSlot(p) { slot =>
          val sql = RestSql.listSchemas(t.catalog, cap)
          execute(t, sql, hiddenFailure(req.requestId), req, slot)(
            reading(r => readStrings(r.rows, cap))
          )
        }
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
        found <- withSlot(p) { slot =>
          execute(t, sql, hiddenFailure(req.requestId), req, slot)(
            reading(r => readStrings(r.rows, cap))
          )
        }
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
        p   <- authenticate(tenant, req)
        _   <- lift(segment(tenantDb))
        sch <- lift(schemaSegment(schema))
        tbl <- lift(objectSegment(name))
        q   <- lift(parseQuery(Route.Table, req.rawQuery))
        fmt <- lift(RestFormat.negotiate(q.format, req.accept))
        t   <- resolveTarget(p, tenant, tenantDb, q.pool)
        // The slot is taken before the catalog lookups: a request over the cap costs nothing.
        described <- withSlot(p) { slot =>
          for
            view <- viewNamed(tenant, t.td, sch, tbl, req.requestId)
            snap <- if view then lift(Right(None)) else snapshotOf(tenant, t.td, q, req.requestId)
            at = RestSql.Target(t.catalog, sch, tbl, snap)
            pr   <- probe(t, at, req, slot)
            _    <- if pr._1.isEmpty then fail(RestError.NotFound) else lift(Right(()))
            _    <- refuseViewTimeTravel(view, q)
            kind <- io(objectTypeOf(t, sch, tbl, req, slot))
          yield (snap, pr, kind)
        }
        (snap, pr, kind) = described
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
          // The table detail negotiates JSON or CSV only (RestFormat.Documents).
          case RestFormat.Arrow | RestFormat.Parquet =>
            throw new IllegalStateException("a streamed format is not a document format")
        RestOk(
          baseHeaders(fmt, pinned(t, q, snap)) ++
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
        f   <- lift(RestFormat.negotiate(q.format, req.accept, rowFormats))
        t   <- resolveTarget(p, tenant, tenantDb, q.pool)
        // min(limit ?: defaultLimit, maxRows, token maxRows); the statement fetches one more.
        limit = t.caller.effectiveMaxRows(cfg.maxRows, q.limit.getOrElse(cfg.defaultLimit))
        // The probe and the data statement share ONE slot, taken before the catalog lookups so a
        // request over the cap costs nothing.
        page <- withSlot(p) { slot =>
          for
            view <- viewNamed(tenant, t.td, sch, tbl, req.requestId)
            sn   <- if view then lift(Right(None)) else snapshotOf(tenant, t.td, q, req.requestId)
            at = RestSql.Target(t.catalog, sch, tbl, sn)
            pr <- probe(t, at, req, slot)
            _  <- refuseViewTimeTravel(view, q)
            rq <- lift(RestResolver.resolve(q, pr._1))
            // Same node as the probe: a soft pin, a vanished node falls back as usual.
            pinned = t.copy(caller = t.caller.copy(preferredNode = Some(pr._2)))
            sql    = RestSql.render(at, rq, limit)
            onFailure = dataFailure(req.requestId, filtered = rq.filters.nonEmpty)
            out <- execute(pinned, sql, onFailure, req, slot) { qr =>
              if f.streamed then
                streamRows(f, qr, limit, req, timeoutFor(t.caller), userKey(p))
                  .map(_.map(Right(_)))
              else
                reading(r => RestResultEncoder.encode(f, r.rows, limit, cfg.maxResponseBytes))(qr)
                  .map(_.map(Left(_)))
            }
          yield (sn, out)
        }
        (sn, out) = page
      yield
        // Truncated when the byte cap, or a server or token row cap (not the client's own limit),
        // cut the page.
        def paging(rows: Long, more: Boolean, cut: Boolean): List[Header] =
          val range = if rows == 0 then "*/*" else s"${q.offset}-${q.offset.toLong + rows - 1}/*"
          val truncated = cut || (more && q.limit.forall(_ > limit))
          Header("Content-Range", range) ::
            Option.when(truncated)(Header("X-QoD-Truncated", "true")).toList
        val head = baseHeaders(f, pinned(t, q, sn)) ++
          sn.map(id => Header("X-QoD-Snapshot", id.toString))
        out match
          case Left(enc) => RestOk(head ++ paging(enc.rows.toLong, enc.more, enc.cut), enc.bytes)
          case Right((bytes, w)) =>
            // The row count is only known once the last batch is out, so the paging headers are
            // trailers, announced here. Many clients and proxies drop trailers: the effective row
            // limit goes up front instead, and a page holding that many rows may continue.
            RestOk(
              head ++ List(
                Header("X-QoD-Limit", limit.toString),
                Header("Trailer", "Content-Range, X-QoD-Truncated")
              ),
              RestBody.Streamed(bytes, IO(paging(w.rows, w.more, w.cut)))
            )
    }

  // ---- step 2: authentication ----------------------------------------------------------------

  /** A blocked client is answered 429 FIRST, before the header is parsed, the PAT store is asked or
    * a JWT is verified: that work is the load the throttle exists to shed. Every authentication
    * failure then counts against the client.
    */
  private def authenticate(tenant: String, req: RestRequest): Step[RestPrincipal] =
    val blocked = authThrottle.blockedFor(req.client).map(RestError.TooManyAuthFailures(_))
    for
      _ <- lift(blocked.toLeft(()))
      p <- EitherT(
        IO.blocking(RestAuth.authenticate(req.authorization, resolvePat, tenant, resolveOidc))
      )
        .leftMap(authFailure(req.client))
      _ <- lift(RestAuth.admit(p, tenant).left.map(authFailure(req.client)))
    yield p

  /** Counts `e` against `client` when it is an authentication failure ([[AuthThrottle.counts]]).
    * The failure that crosses the allowance is itself answered 429. A success is never recorded, so
    * it resets nothing.
    */
  private def authFailure(client: String)(e: RestError): RestError =
    if AuthThrottle.counts(e) && authThrottle.recordFailure(client) then
      RestError.TooManyAuthFailures(cfg.authBlockSec)
    else e

  /** Runs `body` under one in-flight slot of the request's principal, `(tenant, userId)` (for a PAT
    * its OWNER, never the token itself), so minting more tokens buys no extra slots; 429 when a cap
    * is reached. The request's own share of the slot is released when `body` ends, however it ends:
    * the slot is taken and its release registered with no cancellation point in between. Each node
    * call inside it holds a share of its own ([[execute]]) until the node work has actually
    * finished.
    */
  private def withSlot[A](p: RestPrincipal)(body: UserLimiter.Hold => Step[A]): Step[A] =
    EitherT(
      IO.uncancelable { poll =>
        IO(userLimiter.tryAcquire(userKey(p))).flatMap {
          case None       => IO.pure(Left(RestError.TooManyRequests))
          case Some(slot) => poll(body(slot).value).guarantee(IO(slot.release()))
        }
      }
    )

  /** The principal's key in the limiters: `(tenant, userId)`. */
  private def userKey(p: RestPrincipal): (String, String) = (p.user.tenant.getOrElse(""), p.user.id)

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
    * `databases` axis and not be a branch, all three failing alike with 404; a named pool must
    * serve that database; no pool means `PoolPicks.readPoolKey`. The `pools` axis is checked on the
    * RESOLVED key, as `McpDataTools.allowedPool` does ahead of the executor that enforces it again,
    * so an out-of-scope pool is a 403 `acl_denied` rather than the probe's catch-all 404.
    */
  private def resolveTarget(
      p: RestPrincipal,
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

  /** MCP `run_sql`'s own resolution ([[PoolPicks.forDatabase]]); a pool not serving the database is
    * as absent as one that does not exist.
    */
  private def poolKeyFor(
      tenant: String,
      db: String,
      pool: Option[String]
  ): Either[RestError, PoolKey] =
    PoolPicks.forDatabase(sup, tenant, db, pool).left.map {
      case PoolPicks.Miss.NoPool => RestError.PoolUnavailable
      case _                     => RestError.NotFound
    }

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
      req: RestRequest,
      slot: UserLimiter.Hold
  ): Step[(Vector[ProbedColumn], String)] =
    execute(t, RestSql.probe(at), hiddenFailure(req.requestId), req, slot, probeExecutor)(
      reading(r => (ProbedColumn.fromArrow(r.rows.getVectorSchemaRoot.getSchema), r.nodeId))
    )

  /** The table-or-view answer of the detail endpoint, from `information_schema.tables`. The probe
    * cannot tell the two apart; when that lookup is denied (filtered metadata off, no grant on
    * `information_schema`), the type is unknown rather than guessed.
    */
  private def objectTypeOf(
      t: Target,
      schema: String,
      name: String,
      req: RestRequest,
      slot: UserLimiter.Hold
  ) =
    val sql = RestSql.tableType(t.catalog, schema, name)
    execute(t, sql, hiddenFailure(req.requestId), req, slot)(
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
    * has the result closed here (closing is idempotent, so a `use` that already closed it is not
    * harmed).
    *
    * The call holds its own share of the request's `slot`, retained right before the call starts
    * and released only when the node work is over: when the executor fails or raises, or when its
    * result is closed, by `use`, by the hand-off above or, after a timeout or a disconnect, by the
    * bounded wait's late close. An early HTTP answer (504, 503, a cancelled request) never frees
    * it.
    */
  private def execute[A](
      t: Target,
      sql: String,
      onFailure: RouterFailure => RestError,
      req: RestRequest,
      slot: UserLimiter.Hold,
      via: CatalogPreviewHandlers.PreviewExecutor = executor
  )(use: QueryResult => IO[Either[RestError, A]]): Step[A] =
    val node                                    = new AtomicReference(UserLimiter.Hold.Released)
    def releaseNode: IO[Unit]                   = IO(node.get.release())
    val run: IO[Either[RestError, QueryResult]] =
      IO.defer(via(t.caller, t.poolKey, sql))
        .guaranteeCase {
          case Outcome.Succeeded(out) =>
            out.flatMap(r => if r.isRight then IO.unit else releaseNode)
          case _ => releaseNode
        }
        .map {
          case Right(qr) => Right(releasingOnClose(qr, node.get))
          case Left(f)   => Left(onFailure(f))
        }
        .handleError { e =>
          logger.warn(s"rest [${req.requestId}] executor raised ${e.getClass.getName}")
          logger.debug(s"rest [${req.requestId}] executor raised: ${e.getMessage}")
          Left(RestError.UpstreamError)
        }
    // The result as soon as it is delivered, before any cancellation point can lose it.
    val delivered = new AtomicReference[Option[QueryResult]](None)
    val waited    = BoundedWait.closingLate(
      run,
      timeoutFor(t.caller),
      if t.cold then RestError.PoolResuming else RestError.StatementTimeout,
      _.close(),
      qr => IO(delivered.set(Some(qr))),
      beforeStart = IO(node.set(slot.retain()))
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

  /** `qr`, whose close is idempotent and also releases `node`, even when closing the result throws.
    */
  private def releasingOnClose(qr: QueryResult, node: UserLimiter.Hold): QueryResult =
    val closed = new AtomicBoolean(false)
    qr.copy(close =
      () =>
        if closed.compareAndSet(false, true) then
          try qr.close()
          finally node.release()
    )

  private def timeoutFor(caller: ExecCaller): FiniteDuration =
    val base = stmtTimeout.getOrElse(cfg.stmtTimeoutSec.seconds)
    caller.restriction.stmtTimeoutMs.filter(_ > 0).map(_.toLong.millis).fold(base)(_ min base)

  /** The data statement's result as a streamed body in `format` (Arrow IPC through
    * [[RestArrowWriter]], Parquet through [[RestParquetWriter]]), and the encoder, which counts
    * what it sent for the trailers. The `use` of [[execute]]: it owns `qr` from the first
    * instruction on.
    *
    * A Parquet body first takes one of the principal's Parquet slots (one per principal,
    * `maxConcurrentParquet` for the edge; 429 when none is free, `qr` closed), held only from here
    * until the body is released: never across the probe, the catalog lookups or a resume. Each one
    * runs an in-process DuckDB with its own memory limit, so their number is bounded apart from the
    * in-flight cap.
    *
    * The deadline, `maxStreamSec`, runs from here. The first batch (and for Parquet the first
    * bytes) is pulled HERE, before the answer is committed, so a node that fails early still gets
    * an ordinary error response, and a first batch still missing at the deadline the ordinary 504.
    * That wait is forcible ([[ForcedReads]]): a request cancelled during it, or the deadline, ends
    * it at once, closing `qr` (its usual close) so the blocked read returns, and the encoder, the
    * result and the slots are released. From then on the stream owns them: its finalizer closes the
    * encoder and then the result, once, which also releases the node call's share of the in-flight
    * slot and the Parquet slot.
    *
    * The stream fails, and Ember then aborts the connection instead of ending the chunked body
    * cleanly, when a batch after the first byte fails, when the response byte cap is reached with
    * rows left (a client must lower `limit` to page under it), and at the deadline (a slow or
    * stalled reader, or a stalled node: the read in progress is forced to return as above).
    *
    * A body Ember never starts (the client went away before the head was written) would otherwise
    * hold the result forever, so a watchdog releases it after `wait` (at least [[UnstartedFloor]],
    * at most until the deadline) unless the stream has started by then; the two can never both own
    * it.
    */
  private def streamRows(
      format: RestFormat,
      qr: QueryResult,
      limit: Int,
      req: RestRequest,
      wait: FiniteDuration,
      user: (String, String)
  ): IO[Either[RestError, (fs2.Stream[IO, Byte], RowEncoder)]] =
    val rid    = req.requestId
    val closed = IO.blocking(qr.close()).attempt.void
    IO.uncancelable { poll =>
      val slot =
        if format == RestFormat.Parquet then parquetLimiter.tryAcquire(user)
        else Some(UserLimiter.Hold.Released)
      IO.monotonic.flatMap { handed =>
        val deadline = handed + cfg.maxStreamSec.seconds
        slot match
          case None        => closed.as(Left(RestError.TooManyRequests))
          case Some(share) =>
            RowEncoder
              .open(format, qr, limit, cfg.maxResponseBytes, ForcedReads.within(poll, deadline))
              .onCancel(IO(share.release()) *> closed)
              .attempt
              .flatMap {
                case Left(t) =>
                  IO(share.release()) *> closed.as(Left(openFailure(rid, t)))
                case Right(enc) =>
                  // Cancelled while the first batch was read: nobody will stream it.
                  val (bytes, unstarted) = streamOf(enc, qr, share, rid, deadline)
                  poll(IO.unit).onCancel(unstarted) *>
                    IO.monotonic
                      .flatMap { now =>
                        val left = (deadline - now).max(Duration.Zero)
                        (IO.sleep(wait.max(UnstartedFloor).min(left)) *> unstarted).start
                      }
                      .as(Right((bytes, enc)))
              }
      }
    }

  /** The answer to an encoder that failed to open. */
  private def openFailure(rid: String, t: Throwable): RestError = t match
    // The page outgrew the byte cap before its first byte: an ordinary answer still fits.
    case _: CappedArrowReader.ByteCapReached =>
      RestError.invalidParameter("limit", "the page exceeds the response byte cap")
    case _: java.util.concurrent.TimeoutException =>
      logger.warn(s"rest [$rid] no first batch within maxStreamSec")
      RestError.StatementTimeout
    case _ =>
      logger.warn(s"rest [$rid] reading the first batch failed: ${t.getClass.getName}")
      RestError.UpstreamError

  /** The body over an opened encoder, and the release of a body never started (see [[streamRows]]).
    * Exactly one of the stream and that release owns `enc`, `qr` and `share`.
    */
  private def streamOf(
      enc: RowEncoder,
      qr: QueryResult,
      share: UserLimiter.Hold,
      rid: String,
      deadline: FiniteDuration
  ): (fs2.Stream[IO, Byte], IO[Unit]) =
    // 0: handed out, 1: streaming, 2: released.
    val state   = new java.util.concurrent.atomic.AtomicInteger(0)
    val release = enc.close
      .guarantee(IO.blocking(qr.close()))
      .guarantee(IO(share.release()))
      .handleError { t =>
        logger.warn(s"rest [$rid] closing a streamed result failed: ${t.getClass.getName}")
        logger.debug(s"rest [$rid] closing a streamed result failed: ${t.getMessage}")
      }
    // Released other than by a whole body: whatever may still block is forced first.
    val forced    = enc.abort.attempt *> release
    val unstarted = IO(state.compareAndSet(0, 2)).ifM(forced, IO.unit)
    val expired   = IO.monotonic
      .flatMap(now => IO.sleep((deadline - now).max(Duration.Zero)))
      .as(Left(new java.util.concurrent.TimeoutException("maxStreamSec")))
    // The transition to streaming is the acquire of a bracket, so its release is registered with
    // it: no cancellation can fall between the two.
    val bytes = fs2.Stream
      .bracketCase(IO(state.compareAndSet(0, 1))) {
        case (true, Resource.ExitCase.Succeeded) => IO(state.set(2)) *> release
        case (true, _)                           => IO(state.set(2)) *> forced
        case (false, _)                          => IO.unit
      }
      .flatMap { owned =>
        if owned then enc.bytes.interruptWhen(expired)
        else fs2.Stream.raiseError[IO](new IllegalStateException("result already released"))
      }
      .handleErrorWith(t =>
        fs2.Stream.exec(IO(streamFailed(rid, t, enc.cut))) ++ fs2.Stream.raiseError[IO](t)
      )
    (bytes, unstarted)

  /** Logs why a streamed body failed after its first byte: the byte cap is the client's paging
    * signal (INFO), anything else an abort (WARN). Only the class, never the text.
    */
  private def streamFailed(rid: String, t: Throwable, cut: Boolean): Unit =
    if cut then logger.info(s"rest [$rid] stream aborted at the response byte cap")
    else if t.isInstanceOf[java.util.concurrent.TimeoutException] then
      logger.warn(s"rest [$rid] stream aborted past maxStreamSec")
    else logger.warn(s"rest [$rid] stream aborted after the first byte: ${t.getClass.getName}")

  /** Reads a result under ONE finalizer that closes it (which also deregisters the statement from
    * the kill registry), on success, error and cancellation alike; the close is idempotent
    * ([[releasingOnClose]]).
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
