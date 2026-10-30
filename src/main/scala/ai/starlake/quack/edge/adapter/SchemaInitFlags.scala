package ai.starlake.quack.edge.adapter

/** Node ids whose first successful health probe already ran the pool's `CREATE SCHEMA IF NOT
  * EXISTS` on this replica. Written and pruned by the health probe only: it [[retain]]s the set to
  * the nodes it listed after every tick, so a removed node's flag goes with it on every replica.
  */
final class SchemaInitFlags:
  private val flags = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  def done(nodeId: String): Boolean = flags.contains(nodeId)

  def markDone(nodeId: String): Unit = { flags.add(nodeId); () }

  /** Forget every node not in `live`. */
  def retain(live: Set[String]): Unit = { flags.removeIf(id => !live.contains(id)); () }

  /** Visible for tests. */
  private[adapter] def size: Int = flags.size
