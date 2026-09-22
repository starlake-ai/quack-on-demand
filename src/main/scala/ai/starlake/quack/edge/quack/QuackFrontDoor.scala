package ai.starlake.quack.edge.quack

import java.util.Locale
import ai.starlake.quack.edge.{
  EdgeHandshake,
  FlightSqlRouter,
  HandshakeFailure,
  Routed,
  RouterFailure
}
import ai.starlake.quack.edge.adapter.{NodeOutcome, QuackNativeBridge, QuackTransport}
import ai.starlake.quack.edge.admin.AdminSqlParser
import ai.starlake.quack.model.{PoolKey, RunningNode, SqlLiterals, StatementKind}
import ai.starlake.quack.route.{Router, RoutingDecision}
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** The native Quack protocol front door: one `handle(bytes)` per HTTP POST.
  *
  * Identity comes from the token string ([[QuackCredentials]]) through the same handshake as the
  * FlightSQL edge; every statement runs through `FlightSqlRouter.executeWith`, so the ACL, column
  * and row policies, metadata filter, lockdown, routing, stamping, history, audit and kill apply
  * unchanged. The node call is a per-statement [[QuackNodeLink]] and the node's response bytes go
  * back to the client verbatim. See the design note for the message table (section 4.6).
  */
final class QuackFrontDoor(
    router: FlightSqlRouter,
    handshake: EdgeHandshake,
    sessions: QuackSessionRegistry,
    transport: QuackTransport,
    events: ManagerEventSink,
    version: String
) extends LazyLogging:

  import QuackWire.*

  /** Author stamping on this path relies on reading `needs_more_fetch` off the node's response. If
    * that inspection ever fails (pin drift), stamping is switched off for the rest of the process
    * rather than risking a COMMIT that races the client's fetch loop.
    */
  private val stampOnQuack = new AtomicBoolean(true)

  private final case class PrepareOutcome(
      link: QuackNodeLink,
      response: Array[Byte],
      stamped: Boolean,
      txReused: Boolean
  )

  private val Supported = Set(
    Type.PrepareRequest,
    Type.FetchRequest,
    Type.DataRequest,
    Type.Disconnect,
    Type.CancelRequest,
    Type.Acknowledgement,
    Type.HeartbeatRequest
  )

  // -------------------------------------------------------------------------------------------
  // Entry point
  // -------------------------------------------------------------------------------------------

  def handle(bytes: Array[Byte]): IO[Array[Byte]] =
    frame(bytes) match
      case Left(e) => IO.pure(encodeError(s"malformed message: ${e.message}"))
      case Right(f) if f.header.msgType == Type.ConnectionRequest => connect(f)
      case Right(f) if !Supported.contains(f.header.msgType)      =>
        IO.pure(encodeError("Unsupported message type for server"))
      case Right(f) =>
        sessions.get(f.header.connectionId) match
          case None    => IO.pure(encodeError("Invalid connection id"))
          case Some(s) =>
            val now = Instant.now()
            if s.ttlExpired(now) then expire(s).as(encodeError("session expired; reconnect"))
            else if s.leaseExpired(now) then
              expire(s).as(encodeError("Connection heartbeat lease expired"))
            else
              s.renewLease(now)
              dispatch(s, f)

  private def dispatch(s: QuackSession, f: Frame): IO[Array[Byte]] =
    f.header.msgType match
      case Type.PrepareRequest => s.mutex.lock.surround(prepare(s, f))
      case Type.DataRequest    =>
        // Type 9 is APPEND_REQUEST for a generation 1 client and SEND_DATA_REQUEST otherwise.
        if s.hello.maxVersion <= 1L then s.mutex.lock.surround(append(s, f))
        else forwardToCurrent(s, f, encodeError("No active data stream"))
      case Type.FetchRequest => forwardToCurrent(s, f, encodeError("Result has been closed"))
      case Type.CancelRequest | Type.Acknowledgement =>
        forwardToCurrent(s, f, encodeSuccess())
      case Type.HeartbeatRequest =>
        // Keep the node's own lease fresh while the client pauses; failures do not matter here.
        s.current.get() match
          case Some(st) => st.link.forward(f).attempt.as(encodeSuccess())
          case None     => IO.pure(encodeSuccess())
      case Type.Disconnect =>
        s.mutex.lock.surround(finishCurrent(s, commit = false) *> releaseTx(s)) *>
          IO(sessions.unbind(s.connectionId)).as(encodeSuccess())
      case _ => IO.pure(encodeError("Unsupported message type for server"))

  // -------------------------------------------------------------------------------------------
  // CONNECTION_REQUEST
  // -------------------------------------------------------------------------------------------

  private def connect(f: Frame): IO[Array[Byte]] =
    decodeConnectionRequest(f) match
      case Left(e)      => IO.pure(encodeError(s"malformed CONNECTION_REQUEST: ${e.message}"))
      case Right(hello) =>
        QuackCredentials.parse(hello.authString) match
          case Left(reason) => IO.pure(encodeError(s"Authentication failed: $reason"))
          case Right(c)     =>
            handshake.authenticate(c.bearer, c.basic, c.pool, c.tenant, c.superuser) match
              case Left(HandshakeFailure.Unauthenticated(m)) =>
                // The auth chain's own messages already start with the marker.
                val msg =
                  if m.startsWith("Authentication failed") then m else s"Authentication failed: $m"
                IO.pure(encodeError(msg))
              case Left(HandshakeFailure.Unauthorized(m)) => IO.pure(encodeError(m))
              case Right(bound)                           =>
                versionProbe(bound.poolKey, hello).flatMap {
                  case Left(m)              => IO.pure(encodeError(m))
                  case Right(serverVersion) =>
                    sessions.bind(bound, hello).map { s =>
                      events.emit(
                        ManagerEvent.SessionOpened(bound.poolKey.tenant, bound.user, "quack")
                      )
                      logger.info(
                        s"quack session ${s.connectionId.take(8)} bound: user=${bound.user} pool=${bound.poolKey} " +
                          s"client=${hello.clientDuckdbVersion}/${hello.clientPlatform} v${hello.maxVersion}"
                      )
                      encodeConnectionResponse(
                        s.connectionId,
                        ConnectionResponse(
                          serverVersion.getOrElse(s"quack-on-demand $version"),
                          "quack-on-demand",
                          hello.maxVersion,
                          s.heartbeatSec
                        )
                      )
                    }
                }

  /** Best-effort handshake-time check that the pool's nodes speak the client's protocol generation:
    * one open/close against a routable node with the client's own hello. A version rejection is
    * reported now, in one sentence; anything else (no routable node, suspended pool, transport
    * trouble) is left to the first statement. Never wakes a suspended pool.
    */
  private def versionProbe(
      poolKey: PoolKey,
      hello: ConnectionRequest
  ): IO[Either[String, Option[String]]] =
    val node = router.supervisor
      .snapshot(poolKey)
      .flatMap(snap => snap.nodes.find(n => snap.loadOf(n.nodeId).routable))
    node match
      case None    => IO.pure(Right(None))
      case Some(n) =>
        QuackNodeLink.open(transport, n, hello, InvalidIndex).flatMap {
          case Right((link, r)) =>
            link.close().as(Right(Some(r.serverDuckdbVersion).filter(_.nonEmpty)))
          case Left(LinkFailure.Permanent(m))
              if m.toLowerCase(Locale.ROOT).contains("unsupported quack version") =>
            IO.pure(Left(m))
          case Left(_) => IO.pure(Right(None))
        }

  // -------------------------------------------------------------------------------------------
  // PREPARE_REQUEST
  // -------------------------------------------------------------------------------------------

  private def prepare(s: QuackSession, f: Frame): IO[Array[Byte]] =
    decodePrepareRequest(f) match
      case Left(e)     => IO.pure(encodeError(s"malformed PREPARE_REQUEST: ${e.message}"))
      case Right(prep) =>
        // A new statement supersedes the previous one; an abandoned result is never committed.
        finishCurrent(s, commit = false) *> {
          if AdminSqlParser.claims(prep.sql) then admin(s, f, prep)
          else relayPrepare(s, f, prep)
        }

  private def relayPrepare(s: QuackSession, f: Frame, prep: PrepareRequest): IO[Array[Byte]] =
    val cq = f.header.clientQueryId
    router
      .executeWith[PrepareOutcome](
        s.connectionId,
        s.bound.user,
        s.bound.poolKey,
        prep.sql,
        Some(s.bound.effectiveSet),
        relaySend(s, cq, prep),
        source = "quack"
      )
      .flatMap {
        case Left(fail)    => IO.pure(encodeError(fail.reason))
        case Right(routed) => afterPrepare(s, cq, prep.sql, routed)
      }

  private def afterPrepare(
      s: QuackSession,
      cq: Long,
      sql: String,
      routed: Routed[PrepareOutcome]
  ): IO[Array[Byte]] =
    val po   = routed.value
    val kind = router.classifier.classify(sql)
    if kind == StatementKind.Begin then s.txLink.set(Some((routed.nodeId, po.link)))
    val statement = QuackStatement(po.link, routed.nodeId, kind, cq, routed.close)
    statement.pendingCommit.set(po.stamped)
    val moreOpt: Option[Boolean] =
      if messageType(po.response) == Type.PrepareResponse then
        QuackNodeLink.nativeNeedsMore(po.response)
      else Some(false)
    moreOpt match
      case Some(true) =>
        IO {
          s.current.set(Some(statement))
          po.response
        }
      case Some(false) =>
        finishStatement(s, statement, commit = true).map(_.map(encodeError).getOrElse(po.response))
      case None if po.stamped =>
        if stampOnQuack.compareAndSet(true, false) then
          logger.warn(
            "quack front door: cannot read needs_more_fetch off a node PREPARE_RESPONSE (libquackwire " +
              "pin drift?); committing the stamped write now and disabling author stamping on the " +
              "Quack path for this process"
          )
        finishStatement(s, statement, commit = true).map(_.map(encodeError).getOrElse(po.response))
      case None =>
        IO {
          s.current.set(Some(statement))
          po.response
        }

  // -------------------------------------------------------------------------------------------
  // Node calls
  // -------------------------------------------------------------------------------------------

  private def toOutcome[A](f: LinkFailure, ms: Long): NodeOutcome[A] = f match
    case LinkFailure.Transient(m) => NodeOutcome.Transient(m, ms)
    case LinkFailure.Permanent(m) => NodeOutcome.Permanent(m, ms)

  private def dropLink(link: QuackNodeLink, reused: Boolean): IO[Unit] =
    if reused then IO.unit else link.close()

  /** The router's cancel handle for a relayed statement. Synchronous on purpose: an admin kill (a
    * REST thread) and `finishStatement` (which runs it on the blocking pool) both need the
    * DISCONNECT to have reached the node when they return. A link reused inside a client
    * transaction is released by `releaseTx`, not here.
    */
  private def closer(link: QuackNodeLink, reused: Boolean): () => Unit =
    if reused then () => () else () => link.close().unsafeRunSync()

  /** Open (or reuse, inside a client transaction) a node link, run the stamping prelude with the
    * Arrow path's fail-open rule, then hand the link to `body`. Load bookkeeping goes through the
    * adapter exactly as for the Arrow transport.
    */
  private def onLink[A](s: QuackSession, cq: Long)(
      body: (QuackNodeLink, Boolean, Boolean, () => IO[Long]) => IO[NodeOutcome[A]]
  ): FlightSqlRouter.NodeSend[A] =
    (node, _, prelude, recordLoad) =>
      router.adapter.tracked(node, recordLoad) {
        IO.monotonic.flatMap { t0 =>
          val elapsed: () => IO[Long] = () => IO.monotonic.map(t1 => (t1 - t0).toMillis)
          val reuse                   = s.txLink.get().filter(_._1 == node.nodeId).map(_._2)
          val openIO: IO[Either[LinkFailure, (QuackNodeLink, Boolean)]] = reuse match
            case Some(l) => IO.pure(Right((l, true)))
            case None    =>
              QuackNodeLink
                .open(transport, node, s.hello, cq)
                .map(_.map { case (l, _) => (l, false) })
          openIO.flatMap {
            case Left(fail)            => elapsed().map(ms => toOutcome[A](fail, ms))
            case Right((link, reused)) =>
              val stampedPrelude                           = prelude.filter(_ => stampOnQuack.get())
              val preludeIO: IO[Either[LinkFailure, Unit]] = stampedPrelude match
                case None    => IO.pure(Right(()))
                case Some(p) => link.runDiscard(p, cq)
              preludeIO.flatMap {
                case Right(())  => body(link, stampedPrelude.isDefined, reused, elapsed)
                case Left(fail) =>
                  // Fail-open like the Arrow path: the write proceeds unstamped on a fresh link.
                  logger.warn(
                    s"stamping prelude failed on ${node.nodeId}; write proceeds unstamped: ${fail.message}"
                  )
                  dropLink(link, reused) *> QuackNodeLink
                    .open(transport, node, s.hello, cq)
                    .flatMap {
                      case Left(fail2)    => elapsed().map(ms => toOutcome[A](fail2, ms))
                      case Right((l2, _)) => body(l2, false, false, elapsed)
                    }
              }
          }
        }
      }

  private def relaySend(
      s: QuackSession,
      cq: Long,
      prep: PrepareRequest
  ): FlightSqlRouter.NodeSend[PrepareOutcome] =
    (node, wrappedSql, prelude, recordLoad) =>
      onLink[PrepareOutcome](s, cq) { (link, stamped, reused, elapsed) =>
        val req = encodePrepareRequest(link.nodeConnectionId, cq, prep.copy(sql = wrappedSql))
        link.relay(req).flatMap {
          case Left(fail)  => dropLink(link, reused) *> elapsed().map(ms => toOutcome(fail, ms))
          case Right(resp) =>
            if messageType(resp) == Type.ErrorResponse then
              dropLink(link, reused) *> elapsed().map(ms =>
                NodeOutcome.Permanent(decodeErrorMessage(resp).getOrElse("node error"), ms)
              )
            else
              elapsed().map(ms =>
                NodeOutcome
                  .Ok(PrepareOutcome(link, resp, stamped, reused), ms, closer(link, reused))
              )
        }
      }(node, wrappedSql, prelude, recordLoad)

  // -------------------------------------------------------------------------------------------
  // APPEND_REQUEST (generation 1)
  // -------------------------------------------------------------------------------------------

  private def append(s: QuackSession, f: Frame): IO[Array[Byte]] =
    decodeAppendRequest(f) match
      case Left(e)  => IO.pure(encodeError(s"malformed APPEND_REQUEST: ${e.message}"))
      case Right(a) =>
        val cq        = f.header.clientQueryId
        val synthetic = s"INSERT INTO ${quoteIdent(a.schema)}.${quoteIdent(a.table)} SELECT NULL"
        val send: FlightSqlRouter.NodeSend[PrepareOutcome] =
          (node, wrappedSql, prelude, recordLoad) =>
            onLink[PrepareOutcome](s, cq) { (link, stamped, reused, elapsed) =>
              // An append names schema.table with no catalog and a fresh node connection's
              // default catalog is the transient memory db: run the router's USE prefix (the
              // head of the wrapped synthetic statement) on the link first, so the node resolves
              // the table in the pool's catalog exactly as a relayed statement would.
              val usePrefix: Option[String] =
                Option(wrappedSql.trim)
                  .filter(_.toUpperCase(Locale.ROOT).startsWith("USE "))
                  .map(w =>
                    w.indexOf(';') match
                      case -1 => w
                      case i  => w.substring(0, i)
                  )
                  .map(_.trim)
              val useIO: IO[Either[LinkFailure, Unit]] = usePrefix match
                case None    => IO.pure(Right(()))
                case Some(u) => link.runDiscard(u, cq)
              useIO
                .flatMap {
                  case Left(fail) =>
                    dropLink(link, reused) *> elapsed().map(ms => toOutcome(fail, ms))
                  case Right(()) => link.forward(f)
                }
                .flatMap {
                  case Left(fail) =>
                    dropLink(link, reused) *> elapsed().map(ms => toOutcome(fail, ms))
                  case Right(resp) =>
                    if messageType(resp) == Type.ErrorResponse then
                      dropLink(link, reused) *> elapsed().map(ms =>
                        NodeOutcome.Permanent(decodeErrorMessage(resp).getOrElse("node error"), ms)
                      )
                    else
                      elapsed().map(ms =>
                        NodeOutcome
                          .Ok(PrepareOutcome(link, resp, stamped, reused), ms, closer(link, reused))
                      )
                }
            }(node, wrappedSql, prelude, recordLoad)
        finishCurrent(s, commit = false) *>
          router
            .executeWith[PrepareOutcome](
              s.connectionId,
              s.bound.user,
              s.bound.poolKey,
              synthetic,
              Some(s.bound.effectiveSet),
              send,
              source = "quack"
            )
            .flatMap {
              case Left(fail)    => IO.pure(encodeError(fail.reason))
              case Right(routed) =>
                val po = routed.value
                val st = QuackStatement(po.link, routed.nodeId, StatementKind.Dml, cq, routed.close)
                st.pendingCommit.set(po.stamped)
                finishStatement(s, st, commit = true).map(_.map(encodeError).getOrElse(po.response))
            }

  private def quoteIdent(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

  // -------------------------------------------------------------------------------------------
  // Admin dialect: answered by the manager, rendered by a node
  // -------------------------------------------------------------------------------------------

  private def admin(s: QuackSession, f: Frame, prep: PrepareRequest): IO[Array[Byte]] =
    val cq = f.header.clientQueryId
    router
      .execute(s.connectionId, s.bound.user, s.bound.poolKey, prep.sql, Some(s.bound.effectiveSet))
      .flatMap {
        case Left(fail) => IO.pure(encodeError(fail.reason))
        case Right(qr)  =>
          IO.blocking {
            try readStrings(qr.rows)
            finally qr.close()
          }.flatMap { case (cols, rows) =>
            val rendered = renderValues(cols, rows)
            pickReadNode(s.bound.poolKey) match
              case None       => IO.pure(encodeError("no routable node to render the result"))
              case Some(node) =>
                QuackNodeLink.open(transport, node, s.hello, cq).flatMap {
                  case Left(fail)       => IO.pure(encodeError(fail.message))
                  case Right((link, _)) =>
                    link
                      .relay(
                        encodePrepareRequest(link.nodeConnectionId, cq, prep.copy(sql = rendered))
                      )
                      .flatMap(r =>
                        link.close().as(r.fold(fail => encodeError(fail.message), identity))
                      )
                }
          }
      }

  private def pickReadNode(poolKey: PoolKey): Option[RunningNode] =
    router.supervisor.snapshot(poolKey).flatMap { snap =>
      Router.pick(snap, StatementKind.Select, None) match
        case RoutingDecision.Use(id) => snap.nodes.find(_.nodeId == id)
        case _                       => None
    }

  private def readStrings(
      reader: org.apache.arrow.vector.ipc.ArrowReader
  ): (List[String], List[List[Option[String]]]) =
    val root = reader.getVectorSchemaRoot
    val cols = scala.jdk.CollectionConverters
      .ListHasAsScala(root.getSchema.getFields)
      .asScala
      .map(_.getName)
      .toList
    val rows = scala.collection.mutable.ListBuffer.empty[List[Option[String]]]
    while reader.loadNextBatch() do
      val n = root.getRowCount
      var i = 0
      while i < n do
        rows += scala.jdk.CollectionConverters
          .ListHasAsScala(root.getFieldVectors)
          .asScala
          .map(v => Option(v.getObject(i)).map(_.toString))
          .toList
        i += 1
    (cols, rows.toList)

  private def renderValues(cols: List[String], rows: List[List[Option[String]]]): String =
    if rows.isEmpty then
      "SELECT " + cols
        .map(c => s"NULL::VARCHAR AS ${quoteIdent(c)}")
        .mkString(", ") + " WHERE false"
    else
      val values = rows
        .map(r =>
          "(" + r
            .map(_.map(SqlLiterals.duckdbLiteral).getOrElse("NULL::VARCHAR"))
            .mkString(", ") + ")"
        )
        .mkString(", ")
      s"SELECT * FROM (VALUES $values) AS t(${cols.map(quoteIdent).mkString(", ")})"

  // -------------------------------------------------------------------------------------------
  // Statement lifecycle
  // -------------------------------------------------------------------------------------------

  private def forwardToCurrent(s: QuackSession, f: Frame, onNone: Array[Byte]): IO[Array[Byte]] =
    s.current.get() match
      case None     => IO.pure(onNone)
      case Some(st) =>
        st.link.forward(f).flatMap {
          case Left(fail)  => IO.pure(encodeError(fail.message))
          case Right(resp) =>
            val isFetch = f.header.msgType == Type.FetchRequest
            if isFetch && messageType(resp) == Type.FetchResponse && isTerminalFetch(resp) then
              // The client has the whole result. A pending stamping bracket commits now; the
              // link itself stays open for the drain grace (late fetches from the client's other
              // threads must still reach the node), the sweeper releases it.
              val commitIO: IO[Option[String]] =
                if st.pendingCommit.getAndSet(false) then
                  st.link.runDiscard("COMMIT", st.clientQueryId).map {
                    case Left(fail) => Some(commitMessage(fail.message))
                    case Right(())  => None
                  }
                else IO.pure(None)
              commitIO.flatMap {
                case Some(err) => finishStatement(s, st, commit = false).as(encodeError(err))
                case None      => IO(st.drainedAt.compareAndSet(None, Some(Instant.now()))).as(resp)
              }
            else if isFetch && messageType(resp) == Type.ErrorResponse then
              // The node closed or failed the result; release our side too.
              finishStatement(s, st, commit = false).as(resp)
            else IO.pure(resp)
        }

  /** Finish a statement exactly once: the deferred COMMIT when asked and pending, the router's
    * close-and-deregister, and the transaction link release after COMMIT / ROLLBACK. Returns the
    * client-facing error when the COMMIT failed.
    */
  private def finishStatement(
      s: QuackSession,
      st: QuackStatement,
      commit: Boolean
  ): IO[Option[String]] =
    if !st.finished.compareAndSet(false, true) then IO.pure(None)
    else
      // Clear only if this statement is still the current one (identity, not Option equality).
      s.current.updateAndGet(cur => if cur.exists(_ eq st) then None else cur)
      val commitIO: IO[Option[String]] =
        if commit && st.pendingCommit.getAndSet(false) then
          st.link.runDiscard("COMMIT", st.clientQueryId).map {
            case Left(fail) => Some(commitMessage(fail.message))
            case Right(())  => None
          }
        else IO.pure(None)
      commitIO.flatMap { err =>
        IO.blocking(st.close()) *>
          (if st.kind == StatementKind.Commit || st.kind == StatementKind.Rollback then releaseTx(s)
           else IO.unit) *>
          IO.pure(err)
      }

  private def finishCurrent(s: QuackSession, commit: Boolean): IO[Option[String]] =
    s.current.get() match
      case Some(st) => finishStatement(s, st, commit)
      case None     => IO.pure(None)

  private def releaseTx(s: QuackSession): IO[Unit] =
    s.txLink.getAndSet(None) match
      case Some((_, link)) => link.close()
      case None            => IO.unit

  private def commitMessage(m: String): String =
    if m.toLowerCase(Locale.ROOT).contains("conflict") then
      "concurrent write conflict committing the transaction; retry the statement"
    else s"commit failed: $m"

  private def expire(s: QuackSession): IO[Unit] =
    s.mutex.lock.surround(finishCurrent(s, commit = false) *> releaseTx(s)).void *>
      IO(sessions.unbind(s.connectionId)).void

  /** Finish and unbind every session past its lease or TTL, and release every statement drained
    * longer ago than the grace period. Run periodically by the listener.
    */
  def sweep(now: Instant): IO[Unit] =
    sessions.expired(now).traverse_(expire) *>
      sessions.all.traverse_ { s =>
        s.current.get() match
          case Some(st)
              if st.drainedAt
                .get()
                .exists(t => !now.isBefore(t.plusSeconds(QuackFrontDoor.DrainGraceSec))) =>
            s.mutex.lock.surround(finishStatement(s, st, commit = false)).void
          case _ => IO.unit
      }

  /** Manager shutdown: release every node connection. */
  def closeAll(): IO[Unit] =
    sessions.all.traverse_(expire)

  def sessionCount: Int = sessions.size

object QuackFrontDoor:
  /** Seconds a fully served statement keeps its node link for late fetches before the sweeper
    * releases it.
    */
  val DrainGraceSec: Long = 5L
