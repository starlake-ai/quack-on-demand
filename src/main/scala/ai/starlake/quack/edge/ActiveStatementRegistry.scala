package ai.starlake.quack.edge

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

/** One currently-executing statement, as shown to administrators. */
final case class ActiveStatement(
    id: String,
    user: String,
    tenant: String,
    pool: String,
    nodeId: String,
    sql: String,
    startedAt: Instant,
    /** The acting personal-access-token id, when the statement was issued with a PAT bearer. `None`
      * for session-authenticated callers. This is what lets a token revocation find and kill the
      * statements it authorized -- see killByPats.
      */
    patId: Option[String] = None
)

/** Per-replica registry of currently-executing statements.
  *
  * In-memory by design: entries matter only while a statement runs, and the statement-history ring
  * buffer is already per-replica under HA. Kill reaches the owning replica via the qod_kill NOTIFY
  * channel, not via shared state. The cancel handle closes the statement's streaming reader;
  * whether that interrupts node-side execution depends on the quack extension (best-effort).
  */
final class ActiveStatementRegistry(sqlPreviewChars: Int = 500):

  private final case class Entry(info: ActiveStatement, cancel: AtomicReference[() => Unit])

  private val entries = TrieMap.empty[String, Entry]

  /** Register a statement about to be sent to a node; returns its minted id. */
  def register(
      user: String,
      tenant: String,
      pool: String,
      nodeId: String,
      sql: String,
      patId: Option[String] = None,
      now: Instant = Instant.now()
  ): String =
    val id      = UUID.randomUUID().toString
    val preview = if sql.length <= sqlPreviewChars then sql else sql.take(sqlPreviewChars) + "…"
    val info    = ActiveStatement(id, user, tenant, pool, nodeId, preview, now, patId)
    entries.put(id, Entry(info, new AtomicReference(() => ())))
    id

  /** Swap in the real cancel handle once the node starts streaming. */
  def attachCancel(id: String, cancel: () => Unit): Unit =
    entries.get(id).foreach(_.cancel.set(cancel))

  def deregister(id: String): Unit = { entries.remove(id); () }

  def list(): List[ActiveStatement] =
    entries.values.map(_.info).toList.sortBy(_.startedAt).reverse

  /** Best-effort kill: invoke the cancel handle, drop the entry, return it. Handle exceptions are
    * swallowed; teardown may race natural completion.
    */
  def kill(id: String): Option[ActiveStatement] =
    entries.remove(id).map { e =>
      try e.cancel.get()()
      catch case _: Throwable => ()
      e.info
    }

  /** Kill every live statement issued with one of `patIds`: same remove-then-cancel best-effort
    * semantics as [[kill]], applied to the whole match set. Statements with no patId (session
    * callers) never match. Returns the killed entries so the caller can record them.
    */
  def killByPats(patIds: Set[String]): List[ActiveStatement] =
    entries.values.toList
      .filter(_.info.patId.exists(patIds.contains))
      .flatMap(e => kill(e.info.id))
