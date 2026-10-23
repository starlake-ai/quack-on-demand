package ai.starlake.quack.edge.adapter

import cats.effect.{IO, Poll}
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.memory.BufferAllocator

import scala.concurrent.duration.FiniteDuration

/** Forwards SQL to a Quack node through the JNI-backed [[QuackProtocol]] wire driver
  * (libquackwire): Quack protocol v3, a heartbeat lease per connection, and cancel-safe release.
  *
  * It is the manager's only client to its nodes. The embedded fallback (an in-process DuckDB
  * running the `quack` extension over JDBC, behind a JVM-wide mutex) was dropped with the DuckDB
  * 2.0 bump: its JDBC driver no longer exports a result set as Arrow. A platform whose native
  * cannot load refuses to boot instead ([[QuackNativeSupport.bootCheck]]).
  *
  * The class is named `QuackHttpClient` for historical compatibility with the surrounding code; the
  * wire is plain HTTP + `application/vnd.duckdb`.
  *
  * @param allocator
  *   Arrow allocator handed to the returned reader.
  * @param nodeLeaseSec
  *   Quack v3 connection lease (seconds) asked of the node on every connection.
  * @param protocolFactory
  *   Optional test-injection seam: when supplied, the client constructs its [[QuackProtocol]] via
  *   this factory instead of the production [[QuackProtocol.JdkHttpTransport]]. Production code
  *   passes `None`; specs supply a factory backed by a fake [[QuackTransport]].
  */
open class QuackHttpClient(
    allocator: BufferAllocator,
    nodeLeaseSec: Int = QuackProtocol.DefaultNodeLeaseSec,
    protocolFactory: Option[BufferAllocator => QuackProtocol] = None
) extends LazyLogging:

  /** Execute `sql` on the Quack instance reachable at `endpoint` (a `quack:host:port` URI) using
    * `token` for auth, for the principal `authorize` vouches for: its prelude (`CALL
    * system.main.qod_session(...)`, a fresh session handle per call) runs as its own PREPARE first,
    * on the same connection. Returns a streaming [[QuackResponse.Ok]] whose `close` callback the
    * caller must invoke.
    *
    * A failed prelude means `sql` is never sent and the connection is dropped. A prelude the node
    * refuses ([[QuackHttpClient.HandleRefused]]) is Transient (another node may take the
    * statement); one that fails in transport keeps its own error and class. `authorize` throwing
    * (no principal to vouch for) is Permanent.
    *
    * `session` is accepted for the callers' sake and unused: every call opens its own node
    * connection, so no server-side session is shared between calls.
    */
  def query(
      endpoint: String,
      token: String,
      sql: String,
      session: Option[String],
      authorize: () => String
  ): IO[QuackResponse] =
    queryNative(endpoint, token, sql, Some(authorize))

  /** `sql` with no session handle at all: the health probe's canary, which a node that enforces its
    * checks refuses. Never used for a statement anyone asked for.
    */
  def queryWithoutHandle(endpoint: String, token: String, sql: String): IO[QuackResponse] =
    queryNative(endpoint, token, sql, None)

  /** The JNI-backed native path. Opens a fresh [[QuackProtocol]] [[Connection]], runs `sql`, and
    * returns a [[QuackResponse.Ok]] whose `close` callback closes the chained Arrow reader.
    *
    * DISCONNECT-owner rule (plan §7.2 amendment). The reader returned by `Connection.execute` is a
    * [[ChainedQuackArrowReader]] whose `close()` already cascades to `Connection.close()` which
    * fires the `DISCONNECT_MESSAGE`. The `QuackResponse.Ok` close callback therefore MUST call only
    * `reader.close()` -- wrapping a separate `conn.close()` here would double-send DISCONNECT. The
    * reader is the sole owner of the teardown chain.
    *
    * Release on every outcome. A [[Connection]] holds a node session and a heartbeat fiber until
    * closed, so it is acquired under a mask: an error after `open` closes it, and a cancel is only
    * observed at [[checkpoint]] / [[handOver]], where whatever is held gets closed. Callers wrap
    * this in `.timeout`, and the JDK send is uninterruptible, so a cancel typically lands right
    * after PREPARE returns: the reader built from that response is closed, not dropped.
    */
  private def queryNative(
      endpoint: String,
      token: String,
      sql: String,
      authorize: Option[() => String]
  ): IO[QuackResponse] =
    IO.uncancelable { poll =>
      IO.monotonic.flatMap { startNanos =>
        // We need IORuntime in scope so `Connection.execute(sql)` can
        // capture it for the synchronous closures the chained reader
        // calls on `loadNextBatch` / `close`. The global runtime is the
        // same one cats-effect IOApp installs for `Main.run`.
        given IORuntime = IORuntime.global
        nativeProtocol
          .open(endpoint, token)
          .flatMap { conn =>
            (checkpoint(poll, conn.close()) *> install(poll, conn, authorize) *> conn.execute(sql))
              .handleErrorWith(t => conn.close() *> IO.raiseError(t))
          }
          .attempt
          .flatMap {
            case Right(reader) =>
              IO.monotonic.flatMap { endNanos =>
                val elapsedMs = (endNanos - startNanos).toMillis
                // DISCONNECT-owner rule: see scaladoc above. `reader.close()`
                // already cascades to `Connection.close()`; do NOT also call
                // `conn.close()` here.
                handOver(
                  poll,
                  QuackResponse.Ok(reader, elapsedMs, () => reader.close()),
                  IO.blocking(reader.close())
                )
              }
            case Left(t) => mapNativeFailure(t, startNanos)
          }
      }
    }

  /** A cancel point between two masked node calls: a cancel requested meanwhile is observed here,
    * and `release` frees what the step before acquired. Without it a timed-out caller would still
    * send the next PREPARE (and, for a write, run it).
    */
  private def checkpoint(poll: Poll[IO], release: IO[Unit]): IO[Unit] =
    poll(IO.unit).onCancel(release)

  /** Run `authorize`'s prelude on `conn` (its own PREPARE), then a cancel point; nothing for the
    * canary. The node's error on the prelude (its ERROR_RESPONSE) becomes
    * [[QuackHttpClient.HandleRefused]]; a transport failure keeps its own error, Transient for a
    * 5xx, so a 503 is never reported as a refusal.
    */
  private def install(poll: Poll[IO], conn: Connection, authorize: Option[() => String])(using
      IORuntime
  ): IO[Unit] =
    authorize match
      case None       => IO.unit
      case Some(mint) =>
        IO(mint()).flatMap { prelude =>
          conn.executeDiscard(prelude).adaptError { case e: QuackWireError.Permanent =>
            QuackHttpClient.HandleRefused(e.msg)
          }
        } *> checkpoint(poll, conn.close())

  /** Hands `ok` to the caller, or runs `release` when a cancel is already pending: the last step of
    * a masked acquisition, so the response is closed rather than dropped with its connection.
    */
  private def handOver(
      poll: Poll[IO],
      ok: QuackResponse.Ok,
      release: IO[Unit]
  ): IO[QuackResponse] =
    poll(IO.pure[QuackResponse](ok)).onCancel(release)

  /** Map a native-path failure to a [[QuackResponse.Failed]] with elapsed time. */
  private def mapNativeFailure(t: Throwable, startNanos: FiniteDuration): IO[QuackResponse] =
    IO.monotonic.map { endNanos =>
      val err = t match
        case e: QuackWireError.Transient      => QuackError.Transient(e.msg)
        case e: QuackWireError.Permanent      => QuackError.Permanent(e.msg)
        case e: QuackHttpClient.HandleRefused => QuackError.Transient(e.getMessage)
        case other                            =>
          QuackError.Permanent(Option(other.getMessage).getOrElse(other.toString))
      logger.debug(s"native query failed: ${t.getMessage}")
      QuackResponse.Failed(err, (endNanos - startNanos).toMillis)
    }

  /** Stamped-write bracket (EPIC P1). Runs `prelude` (BEGIN + ducklake_set_commit_message), then
    * `sql`, as separate PREPAREs on ONE wire connection (= one server-side DuckDB session), so
    * `sql`'s result reaches the caller byte-identical to the plain path; COMMIT fires through
    * [[StampedCommitArrowReader]] once that result is consumed. Every PREPARE of the bracket (the
    * stamp, `sql`, the COMMIT) follows a fresh session handle from `authorize`; a refused one is
    * never fail-open.
    *
    * Same release discipline as [[queryNative]]. A cancel that lands once `sql` has run closes the
    * inner reader, not the stamped one: the caller was told the write failed, so DISCONNECT rolls
    * the BEGIN back instead of committing it.
    *
    * Fail-open: a prelude failure never blocks the write. The bracket connection is dropped
    * (server-side session teardown rolls the BEGIN back) and the statement is re-sent unstamped on
    * a fresh plain connection, with a warning.
    */
  def queryStamped(
      endpoint: String,
      token: String,
      authorize: () => String,
      prelude: String,
      sql: String
  ): IO[QuackResponse] =
    IO.uncancelable { poll =>
      IO.monotonic.flatMap { startNanos =>
        given IORuntime = IORuntime.global
        nativeProtocol.open(endpoint, token).attempt.flatMap {
          case Left(t)     => mapNativeFailure(t, startNanos)
          case Right(conn) =>
            (checkpoint(poll, conn.close()) *> install(poll, conn, Some(authorize))).attempt
              .flatMap {
                // A refused handle is never fail-open: the write is not sent at all.
                case Left(t)   => conn.close() *> mapNativeFailure(t, startNanos)
                case Right(()) =>
                  conn.executeDiscard(prelude).attempt.flatMap {
                    case Left(t) =>
                      logger.warn(
                        s"stamping prelude failed on $endpoint; write proceeds unstamped: ${t.getMessage}"
                      )
                      // Nothing is held once conn is closed, so the fallback may be cancelled whole.
                      conn.close() *> poll(queryNative(endpoint, token, sql, Some(authorize)))
                    case Right(()) =>
                      (checkpoint(poll, conn.close()) *> install(poll, conn, Some(authorize)) *>
                        conn.execute(sql)).attempt.flatMap {
                        case Left(t) =>
                          // DISCONNECT tears the server session down, rolling the open BEGIN back;
                          // no explicit ROLLBACK round-trip needed.
                          conn.close() *> mapNativeFailure(t, startNanos)
                        case Right(reader) =>
                          IO.monotonic.flatMap { endNanos =>
                            val committed = new java.util.concurrent.atomic.AtomicBoolean(false)
                            // The COMMIT may come long after the statement: it gets its own handle.
                            val commitSync: () => Unit = () =>
                              if committed.compareAndSet(false, true) then
                                (IO(authorize()).flatMap(p => conn.executeDiscard(p)) *>
                                  conn.executeDiscard("COMMIT")).unsafeRunSync()
                            val stamped =
                              new StampedCommitArrowReader(allocator, reader, commitSync)
                            // DISCONNECT-owner rule: stamped.close() delegates to reader.close(),
                            // which cascades Connection.close(); no separate conn.close() here.
                            handOver(
                              poll,
                              QuackResponse.Ok(
                                stamped,
                                (endNanos - startNanos).toMillis,
                                () => stamped.close()
                              ),
                              // reader, not stamped: no COMMIT for a caller that was told it failed.
                              IO.blocking(reader.close())
                            )
                          }
                      }
                  }
              }
        }
      }
    }

  /** Lazily-constructed [[QuackProtocol]]. The transport defaults to
    * [[QuackProtocol.JdkHttpTransport]] over a fresh JDK [[java.net.http.HttpClient]]; tests pass a
    * `protocolFactory` override that wires a stub transport with canned response bytes. Lazy so a
    * client whose `query` a spec overrides never allocates the HttpClient.
    */
  private lazy val nativeProtocol: QuackProtocol =
    protocolFactory match
      case Some(factory) => factory(allocator)
      case None          =>
        new QuackProtocol(
          new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient()),
          allocator,
          nodeLeaseSec
        )

object QuackHttpClient:

  /** The node refused a statement's session handle (its ERROR_RESPONSE to the prelude); the
    * statement itself was never sent. `nodeText` is the node's answer.
    */
  final class HandleRefused(val nodeText: String)
      extends RuntimeException(HandleRefused.Prefix + nodeText)

  object HandleRefused:

    /** How the message of a refused handle starts, in the [[QuackError.Transient]] it fails as. */
    val Prefix: String = "the node refused the statement's session handle: "

    /** The node's answer when `err` is a refused session handle, which [[QuackHttpClient.query]]
      * fails as Transient (another node may take the statement): the health probe tells it apart
      * from any other failure to name the cause.
      */
    def unapply(err: QuackError): Option[String] = err match
      case QuackError.Transient(m) if m.startsWith(Prefix) => Some(m.substring(Prefix.length))
      case _                                               => None
