package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.HandshakeBound
import ai.starlake.quack.model.StatementKind
import cats.effect.IO
import cats.effect.std.Mutex

import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.concurrent.TrieMap

/** The client's current statement: the node connection it runs on and how to finish it. A Quack
  * connection has at most one live statement (a new PREPARE supersedes the previous one, as on a
  * node). `pendingCommit` is set while an author-stamping bracket waits for the client to drain the
  * result; `drainedAt` is stamped by the terminal FETCH, after which the link stays open for a
  * grace period (the generation 1 client fetches from several threads and late fetches must still
  * reach the node) until the sweeper, the next statement or the disconnect finishes it. `close` is
  * the router's close-and-deregister handle.
  */
final case class QuackStatement(
    link: QuackNodeLink,
    nodeId: String,
    kind: StatementKind,
    clientQueryId: Long,
    close: () => Unit
):
  val finished: AtomicBoolean                     = new AtomicBoolean(false)
  val pendingCommit: AtomicBoolean                = new AtomicBoolean(false)
  val drainedAt: AtomicReference[Option[Instant]] = new AtomicReference(None)

/** One client connection to the front door: the bound principal, the client's hello (its protocol
  * generation and DuckDB version drive what the relay may forward), the heartbeat lease, the
  * session TTL, and the current statement. `txLink` is the node connection kept open across
  * statements while the client holds an explicit transaction.
  */
final class QuackSession(
    val connectionId: String,
    val bound: HandshakeBound,
    val hello: QuackWire.ConnectionRequest,
    val heartbeatSec: Long,
    val expiresAt: Instant,
    val mutex: Mutex[IO]
):
  val current: AtomicReference[Option[QuackStatement]]         = new AtomicReference(None)
  val txLink: AtomicReference[Option[(String, QuackNodeLink)]] = new AtomicReference(None)

  /** Set when an admin kill disconnected `txLink` under a client transaction: the client still
    * believes it is inside one, so every statement is refused until it rolls back (or its COMMIT
    * fails, which also ends the transaction on the client side).
    */
  val txAborted: AtomicBoolean = new AtomicBoolean(false)

  @volatile private var leaseDeadline: Option[Instant] =
    if heartbeatSec > 0 then Some(Instant.now().plusSeconds(heartbeatSec)) else None

  def renewLease(now: Instant): Unit =
    if heartbeatSec > 0 then leaseDeadline = Some(now.plusSeconds(heartbeatSec))

  def leaseExpired(now: Instant): Boolean = leaseDeadline.exists(d => !now.isBefore(d))
  def ttlExpired(now: Instant): Boolean   = !now.isBefore(expiresAt)
  def expired(now: Instant): Boolean      = ttlExpired(now) || leaseExpired(now)

/** Per-replica, in-memory session table, like the Flight edge's ConnectionContext. Connection ids
  * are 32 uppercase hex characters from a SecureRandom, the shape a node hands out.
  */
final class QuackSessionRegistry(sessionTtlSec: Long, maxHeartbeatSec: Long):

  private val byId   = TrieMap.empty[String, QuackSession]
  private val random = new SecureRandom()

  private def newId(): String =
    val b = new Array[Byte](16)
    random.nextBytes(b)
    b.map(x => f"${x & 0xff}%02X").mkString

  def bind(bound: HandshakeBound, hello: QuackWire.ConnectionRequest): IO[QuackSession] =
    Mutex[IO].map { m =>
      val heartbeat =
        if hello.heartbeatTimeoutSec <= 0 then 0L
        else math.min(hello.heartbeatTimeoutSec, math.max(1L, maxHeartbeatSec))
      val s = new QuackSession(
        newId(),
        bound,
        hello,
        heartbeat,
        Instant.now().plusSeconds(sessionTtlSec),
        m
      )
      byId.put(s.connectionId, s)
      s
    }

  def get(id: String): Option[QuackSession]     = byId.get(id)
  def unbind(id: String): Option[QuackSession]  = byId.remove(id)
  def all: List[QuackSession]                   = byId.values.toList
  def expired(now: Instant): List[QuackSession] = all.filter(_.expired(now))
  def size: Int                                 = byId.size
