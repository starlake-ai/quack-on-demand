package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.model.PoolKey
import cats.effect.{IO, SyncIO}
import cats.effect.std.Semaphore

import java.sql.{Connection, DriverManager}
import java.util.concurrent.ConcurrentHashMap

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

  /** Single-manager serialization: one in-process permit per pool, the same contract as
    * [[PgPoolLocker]] without a database (and the same no-nesting rule: a second `withLock` on the
    * same key from inside the first waits forever). Wired for every non-HA manager: a scale-down's
    * stop takes seconds on any backend (agent confirmation, pod deletion, process wait), and an
    * unserialized reconcile pass in that window reads the node as dead and respawns it on the
    * stale target.
    */
  def inProcess(): PoolLocker = new PoolLocker:
    private val permits = new ConcurrentHashMap[PoolKey, Semaphore[IO]]()
    def withLock[A](key: PoolKey)(io: IO[A]): IO[A] =
      IO.defer {
        val permit =
          permits.computeIfAbsent(key, _ => Semaphore.in[SyncIO, IO](1).unsafeRunSync())
        permit.permit.surround(io)
      }

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
