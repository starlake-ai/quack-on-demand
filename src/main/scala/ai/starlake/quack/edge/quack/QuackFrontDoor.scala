package ai.starlake.quack.edge.quack

import java.util.Locale
import ai.starlake.quack.edge.{
  EdgeHandshake,
  FlightSqlRouter,
  HandshakeFailure,
  NodeRows,
  Routed,
  RouterFailure
}
import ai.starlake.quack.edge.adapter.{
  NodeHold,
  NodeOutcome,
  QuackHttpAdapter,
  QuackNativeBridge,
  QuackTransport
}
import ai.starlake.quack.edge.admin.AdminSqlParser
import ai.starlake.quack.model.{PoolKey, RunningNode, SqlLiterals, StatementKind}
import ai.starlake.quack.route.{Router, RoutingDecision}
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.{IO, Outcome}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** The native Quack protocol front door: one `handle(bytes)` per HTTP POST.
  *
  * Identity comes from the token string ([[QuackCredentials]]) through the same handshake as the
  * FlightSQL edge; every statement runs through `FlightSqlRouter.executeWith`, so the ACL, column
  * and row policies, metadata filter, routing, stamping, history, audit and kill apply unchanged.
  * The node call is a per-statement [[QuackNodeLink]] and the node's response bytes go back to the
  * client verbatim. See the design note for the message table (section 4.6).
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
      case Type.DataRequest    => forwardToCurrent(s, f, encodeError("No active data stream"))
      case Type.FetchRequest   => forwardToCurrent(s, f, encodeError("Result has been closed"))
      case Type.CancelRequest | Type.Acknowledgement =>
        forwardToCurrent(s, f, encodeSuccess())
      case Type.HeartbeatRequest =>
        // Keep the node's own lease fresh while the client pauses, on every node connection the
        // session holds: the running statement's and an open transaction's (once a statement in
        // the transaction drains, only `txLink` holds the node connection, and the node checks its
        // lease when the next statement arrives). Each link once, by identity. Failures do not
        // matter here.
        val links = (s.current.get().map(_.link).toList ++ s.txLink.get().map(_._2).toList)
          .foldLeft(List.empty[QuackNodeLink])((acc, l) =>
            if acc.exists(_ eq l) then acc else acc :+ l
          )
        links.traverse_(_.forward(f).attempt.void).as(encodeSuccess())
      case Type.Disconnect =>
        s.mutex.lock.surround(finishCurrent(s, commit = false) *> releaseTx(s)) *>
          IO(unbind(s)).as(encodeSuccess())
      case _ => IO.pure(encodeError("Unsupported message type for server"))

  // -------------------------------------------------------------------------------------------
  // CONNECTION_REQUEST
  // -------------------------------------------------------------------------------------------

  private def connect(f: Frame): IO[Array[Byte]] =
    decodeConnectionRequest(f) match
      case Left(e) => IO.pure(encodeError(s"malformed CONNECTION_REQUEST: ${e.message}"))
      case Right(hello)
          if hello.minVersion > QuackFrontDoor.ProtocolVersion ||
            hello.maxVersion < QuackFrontDoor.ProtocolVersion =>
        IO.pure(encodeError(QuackFrontDoor.clientVersionMessage(hello)))
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
              case Left(HandshakeFailure.Unavailable(m))  => IO.pure(encodeError(m))
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
                          QuackFrontDoor.ProtocolVersion,
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
        QuackNodeLink
          .open(
            transport,
            n,
            hello,
            InvalidIndex,
            QuackNodeLink.NoStatements,
            NodeHold.none,
            QuackNodeLink.AlwaysServes
          )
          .flatMap {
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
          s.txAborted.get() match
            case Some(answer) => fenced(s, f, prep, answer)
            case None         =>
              if AdminSqlParser.claims(prep.sql) then admin(s, f, prep)
              else relayPrepare(s, f, prep)
        }

  /** A statement of a fenced session (`txAborted`), whose client transaction the node no longer
    * holds: a ROLLBACK is served on a fresh link, anything else gets the fence's `answer`.
    */
  private def fenced(
      s: QuackSession,
      f: Frame,
      prep: PrepareRequest,
      answer: String
  ): IO[Array[Byte]] =
    router.classifier.classify(prep.sql) match
      case StatementKind.Rollback =>
        // The fence lifts only once the relayed ROLLBACK succeeded (`relayPrepare`).
        relayPrepare(s, f, prep, reopenTx = true)
      case StatementKind.Commit =>
        // DuckDB ends a transaction whose COMMIT fails; so does the client, so the fence lifts
        // here too. What `answer` says of the outcome rests on how the fence came about. A COMMIT
        // that finds it up, or whose own session handle failed, was never sent, and the node rolled
        // the transaction back with its connection: nothing was committed. A COMMIT that itself
        // failed on the link was sent and may have run before its answer was lost, so `linkFailed`
        // made `answer` say the outcome is unknown.
        s.txAborted.set(None)
        IO.pure(encodeError(answer))
      case _ => IO.pure(encodeError(answer))

  /** `reopenTx`: the client's ROLLBACK in a fenced session. Its transaction connection is gone, and
    * DuckDB refuses a ROLLBACK outside a transaction, so the fresh link is put into one first and
    * the client gets a genuine ROLLBACK response.
    */
  private def relayPrepare(
      s: QuackSession,
      f: Frame,
      prep: PrepareRequest,
      reopenTx: Boolean = false
  ): IO[Array[Byte]] =
    val cq = f.header.clientQueryId
    // Catalog reads stay on one node per session: the client joins its schema and table listings
    // on node-local oids (see QuackSession.catalogNode). The pin holds over the node's
    // maxConcurrent cap, and a transient retry onto another node re-pins the session there (the
    // node recorded below is the one that actually served), so a retried first listing still
    // pairs. Residual: when the pinned node turns unroutable, or fails transiently on the SECOND
    // listing, between the two listings, that listing runs elsewhere and the client's catalog
    // comes up without tables until its next refresh (a new ATTACH, or the refresh after a DDL).
    val catalogRead = QuackFrontDoor.readsCatalog(prep.sql)
    // The session's grants as of now: resolved again first when its tenant's policy version moved
    // (SessionGrants), so a revoke reaches this open session on this statement.
    s.bound.grants.currentIO.flatMap {
      case Right(eff) => relayGranted(s, f, prep, reopenTx, cq, catalogRead, eff)
      case Left(fail) =>
        // A ROLLBACK reads and writes no data: a session the grants now refuse can always end the
        // transaction it holds (open, or fenced: the reopened ROLLBACK of `fenced` comes here).
        // Only a lone one: the classifier reads the first statement, so `ROLLBACK; DELETE ...`
        // classifies as a ROLLBACK and would carry the DELETE. And it runs under no grants at all
        // (`rollbackOnly`), never the set of before, so nothing past this check reaches a table.
        val endsTx = !FlightSqlRouter.isMultiStatement(prep.sql) &&
          router.classifier.classify(prep.sql) == StatementKind.Rollback &&
          (s.txLink.get().isDefined || s.txAborted.get().isDefined)
        if endsTx then
          relayGranted(s, f, prep, reopenTx, cq, catalogRead, s.bound.grants.rollbackOnly)
        else grantsRefused(s, prep.sql, fail)
    }

  /** The answer to a statement the session's grants now refuse ([[SessionGrants]]). Inside a client
    * transaction the node transaction is ended first ([[abortTx]]: the node rolls it back), so no
    * later statement, re-granted or not, runs on it or commits it. A refused COMMIT ends the
    * client's transaction too (DuckDB ends a transaction whose COMMIT fails), so it leaves no
    * fence; any other statement, a batch led by a ROLLBACK included, fences the session until its
    * lone ROLLBACK, which the grants never refuse (`relayPrepare`).
    */
  private def grantsRefused(s: QuackSession, sql: String, fail: RouterFailure): IO[Array[Byte]] =
    s.txLink.get() match
      case None =>
        IO.pure(encodeError(fail.reason))
      case Some((_, link)) if router.classifier.classify(sql) == StatementKind.Commit =>
        val answer = QuackFrontDoor.grantsRefusedCommitMessage(fail.reason)
        (abortTx(s, link, answer) *> IO(s.txAborted.set(None))).as(encodeError(answer))
      case Some((_, link)) =>
        val answer = QuackFrontDoor.grantsRefusedTxMessage(fail.reason)
        abortTx(s, link, answer).as(encodeError(answer))

  private def relayGranted(
      s: QuackSession,
      f: Frame,
      prep: PrepareRequest,
      reopenTx: Boolean,
      cq: Long,
      catalogRead: Boolean,
      eff: ai.starlake.quack.ondemand.rbac.EffectiveSet
  ): IO[Array[Byte]] =
    router
      .executeWith[PrepareOutcome](
        s.connectionId,
        s.bound.user,
        s.bound.poolKey,
        prep.sql,
        Some(eff),
        relaySend(s, cq, prep, reopenTx),
        source = "quack",
        preferredNode = if catalogRead then s.catalogNode.get() else None,
        softPinOverCapacity = catalogRead,
        // The one edge that relays SEND_DATA on the statement's own node connection, so the
        // one edge where a 2.0 client's scan_data_from_quack_client can ever be fed.
        clientDataScans = true,
        evaluate = Some(evaluateSend(s, cq)),
        // A computed file location inside a client transaction is computed on the transaction's
        // link and fails hard (CONTRACT Addendum 4 items 25-25b).
        failInTransaction = Some(answer => fenceTx(s, answer)),
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .flatMap {
        case Left(fail) =>
          s.txAborted.get() match
            // The statement failed on its transaction's link (its session handle, or the link
            // itself) and fenced the session (`linkFailed`): the fence was down when the statement
            // began, and no other statement was live to be killed meanwhile. It is answered as the
            // fence answers, so a ROLLBACK runs at once on a fresh link.
            case Some(answer) if !reopenTx => fenced(s, f, prep, answer)
            case _                         => IO.pure(encodeError(fail.reason))
        case Right(routed) =>
          if catalogRead then s.catalogNode.set(Some(routed.nodeId))
          // A failed recovery keeps the fence: the client is still inside its dead transaction.
          if reopenTx then s.txAborted.set(None)
          afterPrepare(s, cq, prep.sql, routed)
      }
      // A link the node answered on but no statement took (a cancel between the router's Ok and
      // `afterPrepare`) is released here, with its hold on the node.
      .guarantee(IO.defer(s.handoff.getAndSet(None).fold(IO.unit)(_.close())))

  private def afterPrepare(
      s: QuackSession,
      cq: Long,
      sql: String,
      routed: Routed[PrepareOutcome]
  ): IO[Array[Byte]] =
    val po        = routed.value
    val kind      = router.classifier.classify(sql)
    val statement = QuackStatement(po.link, routed.nodeId, kind, cq, routed.close)
    statement.pendingCommit.set(po.stamped)
    val moreOpt: Option[Boolean] =
      if messageType(po.response) == Type.PrepareResponse then
        QuackNodeLink.nativeNeedsMore(po.response)
      else Some(false)
    // One step takes the link over from the hand-off: the session holds the statement (and a
    // BEGIN's link as its transaction's) before anything else can be cancelled. A statement served
    // in full is finished at once below, which retires it again.
    IO {
      s.handoff.set(None)
      if kind == StatementKind.Begin then s.txLink.set(Some((routed.nodeId, po.link)))
      s.current.set(Some(statement))
    } *> (moreOpt match
      case Some(true)  => IO.pure(po.response)
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
      case None => IO.pure(po.response))

  // -------------------------------------------------------------------------------------------
  // Node calls
  // -------------------------------------------------------------------------------------------

  private def toOutcome[A](f: LinkFailure, ms: Long): NodeOutcome[A] = f match
    case LinkFailure.Transient(m) => NodeOutcome.Transient(m, ms)
    case LinkFailure.Permanent(m) => NodeOutcome.Permanent(m, ms)
    // The statement was not sent, so another node may take it, as on the Arrow path. Not on a
    // client transaction's link, which `linkFailed` answers first.
    case h: LinkFailure.HandleFailed => NodeOutcome.Transient(h.message, ms)

  private def dropLink(link: QuackNodeLink, reused: Boolean): IO[Unit] =
    if reused then IO.unit else link.close()

  /** The outcome of a node call that failed on `link`, which is released unless a client
    * transaction holds it (`reused`). Any failure on such a link means the transaction cannot go on
    * there: a session handle that failed, whatever the cause (in an aborted transaction DuckDB 2.0
    * refuses every statement but ROLLBACK and COMMIT, the handle's CALL included; a key mismatch;
    * an expiry), and a statement that failed in transport, which may have run before its answer was
    * lost. It is ended ([[abortTx]]) and the statement fails Permanent. Handed to the router as a
    * Transient failure, it would not be retried (the router never retries inside a transaction),
    * but the router would drop its pin and its transaction state while the front door kept the
    * session's `txLink`: the next statement could then be routed to another node and run on a fresh
    * link, outside the client's transaction. Permanent also books nothing against the node's health
    * (a node at fault fails its health probe, which runs on a fresh connection of its own).
    * Anywhere else the failure keeps its class. The node's own answer to a statement (its
    * ERROR_RESPONSE) never comes here: the link is kept and the node's answer to the next handle
    * decides.
    *
    * `clientCommit`: the failed statement is the client's COMMIT, whose answer says what is known
    * of its outcome ([[QuackFrontDoor.txEndedMessage]]). Required, with no default: a caller that
    * left it out would report a COMMIT that may have run as aborted.
    */
  private def linkFailed[A](
      s: QuackSession,
      link: QuackNodeLink,
      reused: Boolean,
      fail: LinkFailure,
      elapsed: () => IO[Long],
      clientCommit: Boolean
  ): IO[NodeOutcome[A]] =
    if reused then
      val answer = QuackFrontDoor.txEndedMessage(fail, clientCommit)
      abortTx(s, link, answer) *> elapsed().map(ms => NodeOutcome.Permanent(answer, ms))
    else dropLink(link, reused) *> elapsed().map(ms => toOutcome[A](fail, ms))

  /** End the client transaction on `link`: DISCONNECT the link, which takes the transaction with it
    * (the node rolls it back), forget it as the session's `txLink`, drop the router's view of the
    * transaction (open, pinned to this node), and fence the session (`txAborted`) with `answer`, so
    * the client, which still believes it is inside a transaction, cannot run on, and above all
    * cannot have a later statement silently auto-commit, until it rolls back.
    */
  private def abortTx(s: QuackSession, link: QuackNodeLink, answer: String): IO[Unit] =
    IO {
      s.txLink.updateAndGet(cur => if cur.exists(_._2 eq link) then None else cur)
      s.txAborted.set(Some(answer))
      router.sessions.invalidatePin(s.connectionId)
    } *> link.close()

  /** The router's close-and-kill handle for a relayed statement. Synchronous on purpose: an admin
    * kill (a REST thread) and `finishStatement` (which runs it on the blocking pool) both need the
    * node to have seen the message when they return.
    *
    * The pinned node refuses CANCEL_REQUEST and has no interrupt: DISCONNECT is the only kill it
    * supports, and a streaming result cannot advance once its connection is gone. A fresh link is
    * therefore simply DISCONNECTed, which both releases it and kills whatever runs on it. A link
    * reused inside a client transaction must outlive the statement (`releaseTx` disconnects it on
    * COMMIT / ROLLBACK), so its handle only acts while the statement is still the session's live
    * one, that is, on an admin kill: it ends the transaction ([[abortTx]]), and the fence answers
    * [[QuackFrontDoor.TxKilledMessage]]. Once `finishStatement` has retired the statement the same
    * handle is a no-op.
    */
  private def closer(
      s: QuackSession,
      link: QuackNodeLink,
      cq: Long,
      reused: Boolean
  ): () => Unit =
    if reused then
      () =>
        s.current.get() match
          case Some(st) if (st.link eq link) && !st.finished.get() =>
            abortTx(s, link, QuackFrontDoor.TxKilledMessage).unsafeRunSync()
          case _ => ()
    else () => link.close().unsafeRunSync()

  /** Whether `node`'s process still serves its node id in the pool: what every link opened on it
    * checks before it sends anything ([[QuackNodeLink]]). False once a crash respawn, a restart or
    * a resume replaced the process under the same id, or once the node left the pool.
    */
  private def stillServes(node: RunningNode): () => Boolean =
    () => router.supervisor.get(node.poolKey).exists(_.nodes.exists(_.sameProcessAs(node)))

  /** A statement of a client transaction whose link was opened on a process that no longer serves
    * the node id the router pinned (a crash respawn, a restart, a resume): nothing is sent on it,
    * since the old connection id means nothing to the new process, or another client's connection.
    * The transaction ends ([[abortTx]]; the link closes without a DISCONNECT) and the statement is
    * answered as a pin to a node that left the pool: aborted, so a COMMIT is told nothing was
    * committed.
    */
  private def replacedTx[A](s: QuackSession, link: QuackNodeLink): IO[NodeOutcome[A]] =
    abortTx(s, link, FlightSqlRouter.PinLostAnswer)
      .as(NodeOutcome.Permanent(FlightSqlRouter.PinLostAnswer, 0L))

  /** Admission of a front-door statement to `node` ([[NodeHold]]), taken before anything is sent to
    * it. On a fresh link the statement holds the node until the link closes, and a draining node
    * refuses it: None, answered Transient (nothing sent, so the router tries another node, as for
    * the Arrow transport). On the link of the session's client transaction it is admitted even
    * while the node drains: the transaction holds the node already, and the drain waits for it
    * (`drainTimeout`) rather than strand it mid-way.
    */
  private def admit(node: RunningNode, txLink: Boolean): Option[NodeHold] =
    if txLink then Some(NodeHold.none) else router.tracker.tryHold(node.nodeId)

  /** Run `use` with the statement's admission to `node` ([[admit]]), the in-flight bookkeeping of
    * the adapter around it. The hold goes to the link `use` opens with it, released when the link
    * closes. It is released here too, as a backstop, whenever `use` leaves no statement on the
    * link: any outcome but Ok, a raise, a cancel.
    */
  private def admitted[A](node: RunningNode, recordLoad: Boolean, txLink: Boolean)(
      use: NodeHold => IO[NodeOutcome[A]]
  ): IO[NodeOutcome[A]] =
    IO.defer {
      admit(node, txLink) match
        case None =>
          IO.pure(NodeOutcome.Transient(QuackHttpAdapter.draining(node.nodeId), 0L))
        case Some(hold) =>
          router.adapter
            .tracked(node, recordLoad, admitDraining = true)(use(hold))
            .guaranteeCase {
              case Outcome.Succeeded(fa) =>
                fa.flatMap {
                  case NodeOutcome.Ok(_, _, _) => IO.unit
                  case _                       => IO(hold.release())
                }
              case _ => IO(hold.release())
            }
    }

  /** Open (or reuse, inside a client transaction) a node link, run the stamping prelude, then hand
    * the link to `body`. The rule is the Arrow path's (`QuackHttpClient.queryStamped`): a failure
    * of the stamp SQL itself is fail-open, the write proceeds unstamped on a fresh link; a failed
    * session handle never is, the write is not sent, and the failure is Transient on a fresh link
    * and ends the transaction on a reused one ([[linkFailed]]). A handle that cannot be minted
    * would fail open too, but a front door session always has a principal bound, and the write's
    * own mint would then fail Permanent, so nothing would go out. Load bookkeeping goes through the
    * adapter exactly as for the Arrow transport; admission to the node, and the hold a fresh link
    * keeps on it until it closes, are [[admitted]]'s.
    */
  private def onLink[A](s: QuackSession, cq: Long)(
      body: (QuackNodeLink, Boolean, Boolean, () => IO[Long]) => IO[NodeOutcome[A]]
  ): FlightSqlRouter.NodeSend[A] =
    (node, _, prelude, recordLoad, principal) =>
      IO.defer {
        val reuse = s.txLink.get().filter(_._1 == node.nodeId).map(_._2)
        val stale = reuse.filterNot(_.node.sameProcessAs(node))
        if stale.isDefined then replacedTx[A](s, stale.get)
        else
          admitted(node, recordLoad, txLink = reuse.isDefined) { hold =>
            IO.monotonic.flatMap { t0 =>
              val elapsed: () => IO[Long] = () => IO.monotonic.map(t1 => (t1 - t0).toMillis)
              // A reused transaction link was opened by this same session for an earlier statement,
              // whose grants this one's may no longer be (SessionGrants): it mints for this
              // statement's principal from now on.
              val authorize = router.adapter.authorizer(node, principal)
              val openIO: IO[Either[LinkFailure, (QuackNodeLink, Boolean)]] = reuse match
                case Some(l) => IO { l.mintWith(authorize); Right((l, true)) }
                case None    =>
                  QuackNodeLink
                    .open(transport, node, s.hello, cq, authorize, hold, stillServes(node))
                    .map(_.map { case (l, _) => (l, false) })
              openIO.flatMap {
                case Left(fail)            => elapsed().map(ms => toOutcome[A](fail, ms))
                case Right((link, reused)) =>
                  val stampedPrelude = prelude.filter(_ => stampOnQuack.get())
                  val preludeIO: IO[Either[LinkFailure, Unit]] = stampedPrelude match
                    case None    => IO.pure(Right(()))
                    case Some(p) => link.runDiscard(p, cq)
                  preludeIO.flatMap {
                    case Right(()) => body(link, stampedPrelude.isDefined, reused, elapsed)
                    // A failed handle is never fail-open: the write is not sent, whatever the cause.
                    // The statement behind a stamp is a write, never a COMMIT.
                    case Left(fail: LinkFailure.HandleFailed) =>
                      linkFailed[A](s, link, reused, fail, elapsed, clientCommit = false)
                    case Left(fail) =>
                      // The stamp SQL itself failed: fail open like the Arrow path, the write proceeds
                      // unstamped on a fresh link whose own PREPAREs each follow a handle. (A mint
                      // failure would land here as well, but never for a front door session, whose
                      // principal is always bound; and the write's own mint would fail Permanent.)
                      logger.warn(
                        s"stamping prelude failed on ${node.nodeId}; write proceeds unstamped: ${fail.message}"
                      )
                      // The fresh link is admitted again: dropping the first one released its hold.
                      dropLink(link, reused) *> (router.tracker.tryHold(node.nodeId) match
                        case None =>
                          IO.pure(NodeOutcome.Transient(QuackHttpAdapter.draining(node.nodeId), 0L))
                        case Some(hold2) =>
                          QuackNodeLink
                            .open(transport, node, s.hello, cq, authorize, hold2, stillServes(node))
                            .flatMap {
                              case Left(fail2)    => elapsed().map(ms => toOutcome[A](fail2, ms))
                              case Right((l2, _)) =>
                                // A raise or a cancel leaves no statement on the fresh link: close it,
                                // which gives its hold back (the backstop in `admitted` holds the
                                // first link's).
                                body(l2, false, false, elapsed).guaranteeCase {
                                  case Outcome.Succeeded(_) => IO.unit
                                  case _                    => l2.close()
                                }
                            })
                  }
              }
            }
          }
      }

  private def relaySend(
      s: QuackSession,
      cq: Long,
      prep: PrepareRequest,
      reopenTx: Boolean = false
  ): FlightSqlRouter.NodeSend[PrepareOutcome] =
    // A BEGIN hands its fresh link to the transaction (`afterPrepare` stores it in `txLink`), so
    // the statement's own closer must not disconnect it; `releaseTx` does, on COMMIT / ROLLBACK.
    val kind         = router.classifier.classify(prep.sql)
    val opensTx      = kind == StatementKind.Begin
    val clientCommit = kind == StatementKind.Commit
    (node, wrappedSql, prelude, recordLoad, principal) =>
      onLink[PrepareOutcome](s, cq) { (link, stamped, reused, elapsed) =>
        val reopen: IO[Either[LinkFailure, Unit]] =
          if reopenTx then link.runDiscard("BEGIN", cq) else IO.pure(Right(()))
        reopen.flatMap {
          // The reopened BEGIN of a client's ROLLBACK: no COMMIT is in flight.
          case Left(fail) => linkFailed(s, link, reused, fail, elapsed, clientCommit = false)
          case Right(())  =>
            link.prepare(prep.copy(sql = wrappedSql), cq).flatMap {
              case Left(fail)  => linkFailed(s, link, reused, fail, elapsed, clientCommit)
              case Right(resp) =>
                if messageType(resp) == Type.ErrorResponse then
                  dropLink(link, reused) *> elapsed().map(ms =>
                    NodeOutcome.Permanent(decodeErrorText(resp).getOrElse("node error"), ms)
                  )
                else
                  // A fresh link is the session's to release from here until `afterPrepare`
                  // stores its statement (`QuackSession.handoff`).
                  IO(if !reused then s.handoff.set(Some(link))) *> elapsed().map(ms =>
                    NodeOutcome.Ok(
                      PrepareOutcome(link, resp, stamped, reused),
                      ms,
                      closer(s, link, cq, reused || opensTx)
                    )
                  )
            }
        }
      }(node, wrappedSql, prelude, recordLoad, principal)

  /** The router's [[FlightSqlRouter.NodeSend]] that reads a small result back for the manager
    * ([[NodeRows]]): a SUMMARIZE's columns, a computed file location's value. Inside a client
    * transaction it runs on the transaction's link, and is refused when the router routed it to
    * another node (it is never computed outside the transaction); else on a fresh link released at
    * once. Failures on the link are handled as [[relaySend]]'s are. The node's chunks are decoded
    * by the native bridge, as on the Arrow path, never encoded.
    */
  private[quack] def evaluateSend(s: QuackSession, cq: Long): FlightSqlRouter.NodeSend[NodeRows] =
    (node, wrappedSql, _, recordLoad, principal) =>
      IO.defer {
        val tx    = s.txLink.get()
        val stale = tx.collect {
          case (id, l) if id == node.nodeId && !l.node.sameProcessAs(node) => l
        }
        if stale.isDefined then replacedTx[NodeRows](s, stale.get)
        else
          admitted(node, recordLoad, txLink = tx.exists(_._1 == node.nodeId)) { hold =>
            IO.monotonic.flatMap { t0 =>
              val elapsed: () => IO[Long] = () => IO.monotonic.map(t1 => (t1 - t0).toMillis)
              val authorize               = router.adapter.authorizer(node, principal)
              val openIO: IO[Either[LinkFailure, (QuackNodeLink, Boolean)]] =
                tx match
                  case Some((id, l)) if id == node.nodeId =>
                    IO { l.mintWith(authorize); Right((l, true)) }
                  case Some((id, _)) =>
                    IO.pure(
                      Left(
                        LinkFailure.Permanent(
                          s"the client transaction's node connection is on $id, not ${node.nodeId}: " +
                            "a statement of the transaction is never computed outside it"
                        )
                      )
                    )
                  case None =>
                    QuackNodeLink
                      .open(transport, node, s.hello, cq, authorize, hold, stillServes(node))
                      .map(_.map { case (l, _) => (l, false) })
              openIO.flatMap {
                case Left(fail)            => elapsed().map(ms => toOutcome[NodeRows](fail, ms))
                case Right((link, reused)) =>
                  link.collect(wrappedSql, cq).flatMap {
                    case Left(fail) =>
                      linkFailed[NodeRows](s, link, reused, fail, elapsed, clientCommit = false)
                    case Right(Left(nodeError)) =>
                      dropLink(link, reused) *> elapsed()
                        .map(ms => NodeOutcome.Permanent(nodeError, ms))
                    case Right(Right(messages)) =>
                      dropLink(link, reused) *> IO.blocking(decoded(messages)).flatMap { rows =>
                        elapsed().map { ms =>
                          rows.fold(
                            why => NodeOutcome.Permanent(why, ms),
                            NodeOutcome.Ok(_, ms, () => ())
                          )
                        }
                      }
                  }
              }
            }
          }
      }

  /** End the client transaction of `s` with `answer` ([[abortTx]]): the router's fence for a
    * computed file location no literal may stand for (CONTRACT Addendum 4 item 25b).
    */
  private def fenceTx(s: QuackSession, answer: String): IO[Unit] =
    s.txLink.get() match
      case Some((_, link)) => abortTx(s, link, answer)
      case None            =>
        IO {
          s.txAborted.set(Some(answer))
          router.sessions.invalidatePin(s.connectionId)
        }

  /** Arrow for the readers of [[decoded]], bounded like what one read keeps
    * ([[QuackNodeLink.MaxCollectBytes]], twice over for the decoded copy), closed with the door.
    */
  private val allocator =
    new org.apache.arrow.memory.RootAllocator(2 * QuackNodeLink.MaxCollectBytes)

  /** The rows of a node's response messages (a PREPARE_RESPONSE, then FETCH_RESPONSEs), through the
    * native bridge.
    */
  private def decoded(messages: List[Array[Byte]]): Either[String, NodeRows] =
    val readers = scala.collection.mutable.ListBuffer.empty[org.apache.arrow.vector.ipc.ArrowReader]
    try
      messages.foreach { m =>
        readers += ai.starlake.quack.edge.adapter.QuackArrowImport
          .importStream(allocator, QuackNativeBridge.extractArrowStream(m))
      }
      NodeRows.readAll(readers.toList)
    catch
      case scala.util.control.NonFatal(e) =>
        Left(s"cannot read the node's result: ${Option(e.getMessage).getOrElse(e.toString)}")
    finally readers.foreach(r => scala.util.Try(r.close()))

  private def quoteIdent(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

  // -------------------------------------------------------------------------------------------
  // Admin dialect: answered by the manager, rendered by a node
  // -------------------------------------------------------------------------------------------

  private def admin(s: QuackSession, f: Frame, prep: PrepareRequest): IO[Array[Byte]] =
    val cq = f.header.clientQueryId
    if AdminSqlParser.isUndrop(prep.sql) && s.txLink.get().isDefined then
      IO.pure(encodeError(ai.starlake.quack.edge.FlightSqlRouter.UndropInTransaction.reason))
    else admin0(s, f, prep, cq)

  private def admin0(
      s: QuackSession,
      f: Frame,
      prep: PrepareRequest,
      cq: Long
  ): IO[Array[Byte]] =
    s.bound.grants.currentIO
      .flatMap {
        case Left(fail) => grantsRefused(s, prep.sql, fail).map(Left(_))
        case Right(eff) =>
          router
            .execute(s.connectionId, s.bound.user, s.bound.poolKey, prep.sql, Some(eff))
            .map(_.left.map(fail => encodeError(fail.reason)))
      }
      .flatMap {
        case Left(answer) => IO.pure(answer)
        case Right(qr)    =>
          IO.blocking {
            try readStrings(qr.rows)
            finally qr.close()
          }.flatMap { case (cols, rows) =>
            val rendered = renderValues(cols, rows)
            pickReadNode(s.bound.poolKey) match
              case None       => IO.pure(encodeError("no routable node to render the result"))
              case Some(node) => renderOn(s, node, prep, cq, rendered)
          }
      }

  /** Render an admin result (`rendered`, a VALUES query) on `node` under the client's PREPARE
    * `prep`, as the system principal, on a link of its own the node is held for. A node picked just
    * before its drain began refuses it: nothing was sent, the client may retry.
    */
  private[quack] def renderOn(
      s: QuackSession,
      node: RunningNode,
      prep: PrepareRequest,
      cq: Long,
      rendered: String
  ): IO[Array[Byte]] =
    router.tracker.tryHold(node.nodeId) match
      case None =>
        IO.pure(encodeError(s"${QuackHttpAdapter.draining(node.nodeId)}; retry"))
      case Some(hold) =>
        QuackNodeLink
          .open(
            transport,
            node,
            s.hello,
            cq,
            router.adapter.authorizer(node, ai.starlake.quack.edge.authz.NodePrincipal.System),
            hold,
            stillServes(node)
          )
          .flatMap {
            case Left(fail)       => IO.pure(encodeError(fail.message))
            case Right((link, _)) =>
              link
                .prepare(prep.copy(sql = rendered), cq)
                .flatMap {
                  case Left(fail)  => link.close().as(encodeError(fail.message))
                  case Right(resp) =>
                    if messageType(resp) != Type.PrepareResponse then link.close().as(resp)
                    else
                      // Same lifecycle as a relayed result: the link stays current while the
                      // node still has chunks to FETCH, and the sweeper, the next statement or
                      // the disconnect releases it.
                      val st = QuackStatement(
                        link,
                        node.nodeId,
                        StatementKind.Select,
                        cq,
                        closer(s, link, cq, reused = false)
                      )
                      if QuackNodeLink.nativeNeedsMore(resp).contains(false) then
                        finishStatement(s, st, commit = false).as(resp)
                      else IO { s.current.set(Some(st)); resp }
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
  ): IO[Option[String]] = IO.uncancelable(_ => finishStatement0(s, st, commit))

  /** [[finishStatement]], which runs it uncancelable: once retired, a statement is always closed,
    * so its link and the link's hold on the node are never left behind.
    */
  private def finishStatement0(
      s: QuackSession,
      st: QuackStatement,
      commit: Boolean
  ): IO[Option[String]] = IO.defer {
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

  /** Forget the session on both tables: the front door's and the router's, which `executeWith`
    * opened under the same connection id on the first statement.
    */
  private def unbind(s: QuackSession): Unit =
    sessions.unbind(s.connectionId)
    router.sessions.close(s.connectionId)

  private def expire(s: QuackSession): IO[Unit] =
    s.mutex.lock.surround(finishCurrent(s, commit = false) *> releaseTx(s)).void *>
      IO(unbind(s)).void

  /** Finish and unbind every session past its lease or TTL, and release every statement drained
    * longer ago than the grace period. Run periodically by the listener. Deferred so the session
    * table is read when the IO runs, not when the listener builds its tick.
    */
  def sweep(now: Instant): IO[Unit] = IO.defer {
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
  }

  /** Manager shutdown: release every node connection. Deferred like [[sweep]]: the listener holds
    * this IO from boot, and it must see the sessions that exist when it runs.
    */
  def closeAll(): IO[Unit] =
    IO.defer(sessions.all.traverse_(expire)) *> IO(scala.util.Try(allocator.close())).void

  def sessionCount: Int = sessions.size

object QuackFrontDoor:
  /** The one Quack protocol version the front door speaks: DuckDB 2.0 clients only. */
  val ProtocolVersion: Long = 3L

  def clientVersionMessage(hello: QuackWire.ConnectionRequest): String =
    s"quack-on-demand requires a DuckDB 2.0 client (Quack protocol $ProtocolVersion); this " +
      s"client (${hello.clientDuckdbVersion}) speaks ${hello.minVersion}..${hello.maxVersion}"

  /** True when `sql` names one of DuckDB's catalog table functions (`duckdb_schemas()`,
    * `duckdb_tables()`, ...), whose `oid` columns are allocated per DuckDB process and therefore
    * differ from node to node. The DuckDB client's ATTACH-time (and post-DDL) catalog sync is two
    * such statements joined on those oids; routed to two different nodes they match nothing and the
    * attached catalog shows every schema but no table.
    *
    * Read from the shared [[ai.starlake.sql.SqlLexer]] tokens, so string literals and comments
    * never count, while a quoted name (`"duckdb_tables"()`), a qualified one
    * (`main.duckdb_tables()`), a comment before the parenthesis and the parenless table form DuckDB
    * also resolves to the function (`FROM duckdb_tables`) all do: any `duckdb_*` word or quoted
    * identifier that is the last segment of a name. A false positive (a column so named) costs only
    * node affinity.
    */
  def readsCatalog(sql: String): Boolean =
    if sql == null || sql.isEmpty then false
    else
      val view = ai.starlake.sql.SqlLexer.engineView(sql)
      val toks = ai.starlake.sql.SqlLexer.tokens(view).filterNot(_.isTrivia)
      def name(t: ai.starlake.sql.SqlLexer.Token): Option[String] =
        val text = view.substring(t.start, t.end)
        t.kind match
          case ai.starlake.sql.SqlLexer.Kind.Word                             => Some(text)
          case ai.starlake.sql.SqlLexer.Kind.Literal if text.startsWith("\"") =>
            Some(text.drop(1).stripSuffix("\"").replace("\"\"", "\""))
          case _ => None
      // The lexer reads a lone `.` as a Number token, so test the character, not the kind.
      def qualifies(i: Int): Boolean = toks.lift(i + 1).exists(n => view.charAt(n.start) == '.')
      toks.indices.exists { i =>
        name(toks(i)).exists(_.toLowerCase(Locale.ROOT).startsWith("duckdb_")) && !qualifies(i)
      }

  /** Answer to a statement inside a client transaction that the session's grants now refuse
    * (`reason`), and to every statement after it until ROLLBACK: the transaction was ended.
    */
  def grantsRefusedTxMessage(reason: String): String =
    s"transaction aborted: $reason; ROLLBACK to continue"

  /** Answer to a client's COMMIT that the session's grants now refuse (`reason`): it was never
    * sent, so nothing was committed, and the transaction's link was released. The release (a
    * DISCONNECT, on which the node rolls the transaction back) is best effort, so the answer does
    * not claim the rollback itself: a node that missed it ends the transaction with the connection.
    */
  def grantsRefusedCommitMessage(reason: String): String =
    s"$reason; the transaction was ended, nothing was committed"

  /** Answer to every statement a client sends inside a transaction an admin kill tore down. */
  val TxKilledMessage: String =
    "transaction aborted: a statement was killed by an administrator; ROLLBACK to continue"

  /** Answer to a statement that failed (`f`) on its client transaction's link, and to every
    * statement after it until ROLLBACK. When the node says the transaction is aborted (DuckDB 2.0
    * then refuses every statement but ROLLBACK and COMMIT, the handle's CALL included), its own
    * error, the one DuckDB would have answered the statement with; otherwise what failed, and the
    * way out. Any failure that is not a handle's is worded to hold for a statement lost in
    * transport and for a handle that could not be minted (no principal to vouch for) alike: in both
    * a statement could not run on the connection.
    *
    * `commit`: the statement is the client's COMMIT. Its session handle goes out first, so when
    * that failed (`HandleFailed`) the COMMIT was never sent: the answer is the aborted
    * transaction's, as above. Any other failure may have come after the COMMIT went out, and it may
    * have run before its answer was lost: the client is told the outcome is unknown, not that
    * nothing was committed, and not to ROLLBACK (its transaction is over either way). (A handle
    * that could not be minted sent nothing either, but it is a `Permanent` like any other and is
    * not told apart here; a front door session always has a principal, so it does not occur, and
    * unknown is the safe side to err on.)
    */
  def txEndedMessage(f: LinkFailure, commit: Boolean): String = f match
    case h: LinkFailure.HandleFailed
        if h.refused && h.detail.contains("Current transaction is aborted") =>
      h.detail
    case h: LinkFailure.HandleFailed =>
      s"transaction aborted: ${LinkFailure.handleCause(h.refused)} (${h.detail}); " +
        "ROLLBACK to continue"
    case other if commit =>
      s"the transaction's outcome is unknown: its node connection failed during COMMIT " +
        s"(${other.message}); check the data before retrying"
    case other =>
      s"transaction aborted: a statement could not run on the transaction's node connection " +
        s"(${other.message}); ROLLBACK to continue"

  /** Seconds a fully served statement keeps its node link for late fetches before the sweeper
    * releases it.
    */
  val DrainGraceSec: Long = 5L
