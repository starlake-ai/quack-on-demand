package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.model.PoolKey
import cats.effect.IO

import java.sql.{Connection, DriverManager}

/** Serializes pool mutations across manager replicas. A pool-mutating handler on any replica and
  * the leader's reconcile pass take the same per-pool lock, so neither ever sees half-written node
  * rows.
  */
trait PoolLocker:
  def withLock[A](key: PoolKey)(io: IO[A]): IO[A]

object PoolLocker:
  /** Non-HA default: no cross-process locking (single manager). */
  val noop: PoolLocker = new PoolLocker:
    def withLock[A](key: PoolKey)(io: IO[A]): IO[A] = io

/** Session advisory lock on a dedicated connection per acquisition (mirrors DuckLakeInitializer).
  * Session scope means a crashed holder's lock frees as soon as Postgres notices the dead session -
  * no lease bookkeeping. Pool mutations are rare and can be slow (pod startup), so one short-lived
  * connection per mutation is fine.
  *
  * NOTE: each acquisition opens its OWN connection and takes a SESSION advisory lock. Nesting two
  * `withLock` calls with the SAME key from the same logical flow would self-deadlock (the second
  * connection blocks forever waiting on a lock held by the first). PoolSupervisor is wired so no
  * locked method invokes another locked method with the same key.
  */
final class PgPoolLocker(jdbcUrl: String, user: String, password: String) extends PoolLocker:

  Class.forName("org.postgresql.Driver")

  def withLock[A](key: PoolKey)(io: IO[A]): IO[A] =
    IO.blocking(acquire(key)).bracket(_ => io)(c => IO.blocking(c.close()))

  private def acquire(key: PoolKey): Connection =
    val c = DriverManager.getConnection(jdbcUrl, user, password)
    try
      val st = c.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")
      try
        st.setString(1, s"qod-pool:${key.tenant}/${key.tenantDb}/${key.pool}")
        st.execute()
      finally st.close()
      c
    catch
      case t: Throwable =>
        try c.close()
        catch case _: Throwable => ()
        throw t
