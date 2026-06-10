package ai.starlake.quack.edge

import ai.starlake.quack.model.{PoolKey, StatementKind}
import scala.collection.concurrent.TrieMap

/** Tracks one logical session per FlightSQL connection. Thread-safe via TrieMap. */
final class SessionRegistry:
  private val sessions = TrieMap.empty[String, Session]

  def open(connectionId: String, user: String, poolKey: PoolKey): Session =
    val s = Session(connectionId, user, poolKey, pinnedNodeId = None, txOpen = false)
    sessions.put(connectionId, s)
    s

  def get(connectionId: String): Option[Session] = sessions.get(connectionId)

  def close(connectionId: String): Unit = { sessions.remove(connectionId); () }

  /** Update session state after a statement executed on `executedOn` node. */
  def onStatement(connectionId: String, kind: StatementKind, executedOn: String): Unit =
    sessions.updateWith(connectionId) {
      case Some(s) =>
        Some(kind match
          case StatementKind.Begin    => s.copy(pinnedNodeId = Some(executedOn), txOpen = true)
          case StatementKind.Commit   => s.copy(pinnedNodeId = None, txOpen = false)
          case StatementKind.Rollback => s.copy(pinnedNodeId = None, txOpen = false)
          case _                      => s)
      case None => None
    }

  /** Drop the pinned node + transaction state. Used when a node dies mid-tx. */
  def invalidatePin(connectionId: String): Unit =
    sessions.updateWith(connectionId) {
      case Some(s) => Some(s.copy(pinnedNodeId = None, txOpen = false))
      case None    => None
    }

  /** Current number of open sessions. */
  def size: Int = sessions.size

  /** Current number of open sessions inside an explicit transaction. */
  def inTransactionCount: Int =
    sessions.values.count(_.txOpen)
