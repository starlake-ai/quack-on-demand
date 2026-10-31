package ai.starlake.quack.edge.authz

/** A control-plane failure as a log line may name it: the exception's class, and the SQLState of
  * the first `SQLException` in its cause chain when one carries a state. Never the message: for a
  * write pgjdbc quotes the failing row in it ("Failing row contains (...)"), a credential included.
  */
object FailureText:

  /** The most causes followed: a chain that loops ends here. */
  private val MaxCauses = 16

  def of(e: Throwable): String =
    val name = e.getClass.getSimpleName
    sqlState(e).fold(name)(state => s"$name (SQLState $state)")

  private def sqlState(e: Throwable): Option[String] =
    Iterator
      .iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .take(MaxCauses)
      .collectFirst {
        case s: java.sql.SQLException if s.getSQLState != null => s.getSQLState
      }
