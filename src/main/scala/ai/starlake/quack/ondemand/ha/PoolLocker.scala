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
    * unserialized reconcile pass in that window reads the node as dead and respawns it on the stale
    * target.
    */
  def inProcess(): InProcessPoolLocker = new InProcessPoolLocker

/** [[PoolLocker.inProcess]]. A pool's permit lives only while someone holds or waits for it: each
  * `withLock` counts itself in before waiting and out once done (failed or cancelled included), and
  * the last one out removes the permit. Counting in and out run under the map's per-key lock, so
  * every caller that overlaps another one of the same key shares its permit, and a caller that
  * finds none makes a fresh one that nobody else can be holding. The map therefore holds only the
  * pools being mutated right now, not every pool this manager ever locked (a deleted pool
  * included).
  */
final class InProcessPoolLocker extends PoolLocker:

  private final class Permit(val semaphore: Semaphore[IO], var users: Int)

  private val permits = new ConcurrentHashMap[PoolKey, Permit]()

  private def enter(key: PoolKey): Semaphore[IO] =
    permits
      .compute(
        key,
        (_, current) =>
          if current == null then new Permit(Semaphore.in[SyncIO, IO](1).unsafeRunSync(), 1)
          else
            current.users += 1
            current
      )
      .semaphore

  private def leave(key: PoolKey): Unit =
    permits.computeIfPresent(
      key,
      (_, current) =>
        current.users -= 1
        if current.users == 0 then null else current
    )
    ()

  def withLock[A](key: PoolKey)(io: IO[A]): IO[A] =
    IO.delay(enter(key)).bracket(_.permit.surround(io))(_ => IO.delay(leave(key)))

  /** Visible for tests: pools with a holder or a waiter. */
  private[ha] def permitKeys: Int = permits.size

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
