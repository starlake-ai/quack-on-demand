package ai.starlake.quack.edge.authz

import cats.effect.IO
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import com.typesafe.scalalogging.Logger
import org.http4s.{Request, Response, Status}
import org.typelevel.ci.CIString

import java.util.concurrent.TimeUnit

/** What the node-to-manager routes (decide, credentials) share: how a call names its node and
  * signature, and the order they are checked in. Both headers are checked before the body is read,
  * a declared `Content-Length` over the bound is refused before the body streams, and only then is
  * the body read (at most one byte past the bound, so an undeclared length is caught too).
  */
object NodeCalls:

  /** The longest node id accepted. `PoolSupervisor.nodeId` composes
    * `quack-<tenant>-<tenantDb>-<pool>-<index>`: the composed tenant-db name `<tenant>_<db>` is at
    * most `Names.MaxLength` (63) characters, so the tenant at most 61, the pool at most 63 and the
    * index at most 10 digits, 206 characters in all. 255 leaves room for other composers.
    */
  val MaxNodeIdLength: Int = 255

  val NodeIdText: scala.util.matching.Regex =
    s"[A-Za-z0-9][A-Za-z0-9._-]{0,${MaxNodeIdLength - 1}}".r

  val MacText: scala.util.matching.Regex = "[0-9a-f]{64}".r

  /** The refusal of a call whose node or signature header is missing or malformed. */
  val MissingHeaders: String = "missing or malformed X-QoD-Node or X-QoD-Signature"

  val BodyTooLarge: String = "request body too large"

  /** `req` answered by `answer(nodeId, signature, body)`, run blocking, once both headers are well
    * formed and the body is within `maxBodyBytes`; else 401 ([[MissingHeaders]], through `refused`,
    * with the body unread) or 413.
    */
  def serve(req: Request[IO], maxBodyBytes: Int)(
      refused: (String, String) => (Status, String)
  )(answer: (String, String, Array[Byte]) => (Status, String)): IO[Response[IO]] =
    def respond(r: (Status, String)): Response[IO] = Response[IO](r._1).withEntity(r._2)
    val node      = req.headers.get(CIString("X-QoD-Node")).map(_.head.value)
    val signature = req.headers.get(CIString("X-QoD-Signature")).map(_.head.value)
    (node.filter(NodeIdText.matches), signature.filter(MacText.matches)) match
      case (Some(nodeId), Some(sig)) =>
        if req.contentLength.exists(_ > maxBodyBytes) then
          IO.pure(respond((Status.PayloadTooLarge, BodyTooLarge)))
        else
          req.body.take(maxBodyBytes.toLong + 1).compile.to(Array).flatMap { body =>
            if body.length > maxBodyBytes then
              IO.pure(respond((Status.PayloadTooLarge, BodyTooLarge)))
            else IO.blocking(answer(nodeId, sig, body)).map(respond)
          }
      case _ =>
        IO(respond(refused(node.filter(NodeIdText.matches).getOrElse("unknown"), MissingHeaders)))

  /** Which refusals a route logs at WARN: the first per node id (kept [[RefusalLog.RememberMs]]),
    * at most [[RefusalLog.MaxWarnsPerMinute]] a minute; every other one at DEBUG. These routes sit
    * on the public REST port, so an unauthenticated flood must not flood the log. Memory is bounded
    * by [[RefusalLog.MaxNodes]] node ids.
    */
  final class RefusalLog(logger: Logger, clock: () => Long = () => System.currentTimeMillis()):
    import RefusalLog.*

    private val seen: Cache[String, java.lang.Boolean] =
      Caffeine
        .newBuilder()
        .maximumSize(MaxNodes)
        .expireAfterWrite(RememberMs, TimeUnit.MILLISECONDS)
        .executor(_.run())
        .build[String, java.lang.Boolean]()

    private var windowStart   = 0L
    private var warnsInWindow = 0

    private def underCap(): Boolean = synchronized {
      val now = clock()
      if now - windowStart >= 60_000L then
        windowStart = now
        warnsInWindow = 0
      if warnsInWindow < MaxWarnsPerMinute then
        warnsInWindow += 1
        true
      else false
    }

    /** Logs `line`, a refusal of a call from `nodeId`. */
    def refused(nodeId: String, line: => String): Unit =
      if seen.getIfPresent(nodeId) == null && underCap() then
        seen.put(nodeId, java.lang.Boolean.TRUE)
        logger.warn(line)
      else logger.debug(line)

  object RefusalLog:
    val MaxNodes: Long         = 10_000L
    val RememberMs: Long       = 60 * 60_000L
    val MaxWarnsPerMinute: Int = 60
