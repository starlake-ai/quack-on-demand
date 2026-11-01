package ai.starlake.quack.ondemand.state

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.Connection
import scala.util.Try

/** One control-plane transaction bound to the calling thread, which every Postgres-backed store of
  * that database joins ([[PostgresControlPlaneStore]], [[FederatedSourceStore]]): while it is
  * bound, their `withConn` hands out the transaction's connection instead of opening one, so every
  * write made by the thread inside [[run]] commits together or not at all.
  *
  * The connection a store sees is guarded: `commit`, `rollback`, `setAutoCommit` and `close` are
  * no-ops on it, so a store method that manages its own transaction (`ensureBuiltins`,
  * `createTenantWithBuiltins`, ...) neither commits the outer one early nor returns its connection
  * to the pool. A store method that swallows a failed statement and carries on leaves the
  * transaction aborted: Postgres then refuses every later statement, the failure surfaces, and
  * [[run]] rolls everything back.
  *
  * A store of another database cannot join: its `withConn` throws rather than writing outside the
  * transaction its caller asked for.
  */
private[state] object ControlPlaneTx:

  private final case class Bound(jdbcUrl: String, guarded: Connection)

  private val current = new ThreadLocal[Bound]

  /** The bound transaction's connection when this thread holds one for `jdbcUrl`; None when it
    * holds none. Throws when it holds one for another database.
    */
  def joined(jdbcUrl: String): Option[Connection] =
    Option(current.get).map { b =>
      if b.jdbcUrl != jdbcUrl then
        throw new IllegalStateException(
          s"a control-plane transaction on ${b.jdbcUrl} is open on this thread; a store of " +
            s"$jdbcUrl cannot join it"
        )
      b.guarded
    }

  /** Runs `f` in one transaction on a connection from `open`, committed when `f` returns and rolled
    * back when it throws. Nested calls on the same database join the outer transaction.
    */
  def run[A](jdbcUrl: String, open: () => Connection)(f: => A): A =
    joined(jdbcUrl) match
      case Some(_) => f
      case None    =>
        val c = open()
        try
          c.setAutoCommit(false)
          current.set(Bound(jdbcUrl, guard(c)))
          try
            val a = f
            c.commit()
            a
          catch
            case t: Throwable =>
              // A failing rollback (connection already broken) must not hide the original error.
              Try(c.rollback())
              throw t
          finally
            current.remove()
            Try(c.setAutoCommit(true))
        finally c.close()

  private val Ignored = Set("commit", "rollback", "setAutoCommit", "close")

  private def guard(c: Connection): Connection =
    val handler = new InvocationHandler:
      def invoke(proxy: Any, m: Method, args: Array[AnyRef]): AnyRef =
        val arity = if args == null then 0 else args.length
        m.getName match
          // rollback(Savepoint) is delegated: it stays inside the transaction.
          case name if Ignored.contains(name) && !(name == "rollback" && arity == 1) => null
          case "getAutoCommit" => java.lang.Boolean.FALSE
          case _               =>
            try if args == null then m.invoke(c) else m.invoke(c, args*)
            catch case e: InvocationTargetException => throw e.getCause
    Proxy
      .newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), handler)
      .asInstanceOf[Connection]
