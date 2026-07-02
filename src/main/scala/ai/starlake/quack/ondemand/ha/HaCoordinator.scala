package ai.starlake.quack.ondemand.ha

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.postgresql.PGConnection

import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration

/** One dedicated Postgres connection per replica carrying both the manager leader-election session
  * advisory lock and the LISTEN subscriptions.
  *
  * Leadership is a session advisory lock (`pg_try_advisory_lock` on a fixed key): Postgres frees it
  * the instant the holding session dies, so a crashed leader is replaced within one retry interval.
  * Handlers receive at most one dispatch per channel per tick (notifications coalesce; payload =
  * latest). Every failure path demotes first, then drops the connection; the next tick reconnects.
  * Never pool this connection: HikariCP would recycle it and silently drop both the lock and the
  * subscriptions.
  */
final class HaCoordinator(
    jdbcUrl: String,
    user: String,
    password: String,
    retry: FiniteDuration,
    handlers: Map[String, String => Unit]
) extends LazyLogging:

  Class.forName("org.postgresql.Driver")

  private val LockKey = "qod-manager-leader"

  private val leader                             = new AtomicBoolean(false)
  private val closed                             = new AtomicBoolean(false)
  @volatile private var conn: Option[Connection] = None

  def isLeader: Boolean = leader.get()

  /** One synchronous pass: ensure connection + LISTENs, try the lock, drain and dispatch
    * notifications. Demotes and disconnects on any error.
    */
  def tickNow(): Unit =
    if !closed.get() then
      try
        val c = conn.getOrElse(connect())
        if !leader.get() then tryAcquire(c)
        drainNotifications(c)
      catch
        case t: Throwable =>
          if leader.getAndSet(false) then logger.warn(s"ha: demoted (${t.getMessage})")
          else logger.debug(s"ha: tick failed (${t.getMessage})")
          closeQuietly()

  def loop: IO[Unit] =
    (IO.blocking(tickNow()) *> IO.sleep(retry)).foreverM.void

  def close(): Unit =
    closed.set(true)
    leader.set(false)
    closeQuietly()

  private def connect(): Connection =
    val props = new java.util.Properties()
    props.setProperty("user", user)
    props.setProperty("password", password)
    props.setProperty("ApplicationName", "qod-ha-coordinator")
    val c = DriverManager.getConnection(jdbcUrl, props)
    conn = Some(c)
    handlers.keys.foreach { ch =>
      val st = c.createStatement()
      try st.execute(s"LISTEN $ch")
      finally st.close()
    }
    logger.info(s"ha: coordinator connected (channels: ${handlers.keys.mkString(", ")})")
    c

  private def tryAcquire(c: Connection): Unit =
    val st = c.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")
    try
      st.setString(1, LockKey)
      val rs = st.executeQuery()
      if rs.next() && rs.getBoolean(1) then
        leader.set(true)
        logger.info("ha: acquired leadership")
    finally st.close()

  private def drainNotifications(c: Connection): Unit =
    // a round-trip makes pgjdbc surface pending async notifications
    val ping = c.createStatement()
    try ping.execute("SELECT 1")
    finally ping.close()
    val notes = Option(c.unwrap(classOf[PGConnection]).getNotifications())
      .getOrElse(Array.empty[org.postgresql.PGNotification])
    if notes.nonEmpty then
      val latestPerChannel = notes.groupBy(_.getName).view.mapValues(_.last.getParameter)
      latestPerChannel.foreach { case (channel, payload) =>
        handlers.get(channel).foreach { h =>
          try h(payload)
          catch
            case t: Throwable =>
              logger.warn(s"ha: handler for $channel failed: ${t.getMessage}")
        }
      }

  private def closeQuietly(): Unit =
    conn.foreach(c =>
      try c.close()
      catch case _: Throwable => ()
    )
    conn = None
