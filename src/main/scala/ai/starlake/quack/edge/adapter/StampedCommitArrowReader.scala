package ai.starlake.quack.edge.adapter

import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.dictionary.Dictionary
import org.apache.arrow.vector.ipc.ArrowReader

import java.util.concurrent.atomic.AtomicBoolean

/** Decorates the result reader of a stamped write (EPIC P1 bracket) so the transaction COMMIT fires
  * exactly once, after the client has consumed the statement's own result:
  *
  *   - On stream exhaustion (`loadNextBatch` returning false): COMMIT runs synchronously and a
  *     failure propagates to the caller as a stream error BEFORE the stream completes, so a commit
  *     conflict is never a silent success.
  *   - On `close()` without a full drain (e.g. a JDBC caller that read the Count row and moved on):
  *     COMMIT is attempted best-effort BEFORE the underlying close cascades DISCONNECT (the wire
  *     connection must still be alive for the round-trip); a failure at that point is logged, as
  *     the result has already been streamed.
  *
  * `commitSync` MUST be idempotent (the caller guards with its own AtomicBoolean) because both
  * paths can run for one reader. Delegation mirrors [[ChainedQuackArrowReader]]: the parent class's
  * `ensureInitialized` machinery is never engaged.
  */
private[adapter] final class StampedCommitArrowReader(
    allocator: BufferAllocator,
    underlying: ArrowReader,
    commitSync: () => Unit
) extends ArrowReader(allocator)
    with LazyLogging:

  private val commitAttempted = AtomicBoolean(false)

  override def loadNextBatch(): Boolean =
    val more = underlying.loadNextBatch()
    if !more && commitAttempted.compareAndSet(false, true) then commitSync()
    more

  override def getVectorSchemaRoot: VectorSchemaRoot = underlying.getVectorSchemaRoot

  override def getDictionaryVectors: java.util.Map[java.lang.Long, Dictionary] =
    underlying.getDictionaryVectors

  override def bytesRead(): Long = underlying.bytesRead()

  override def close(closeReadSource: Boolean): Unit =
    if commitAttempted.compareAndSet(false, true) then
      try commitSync()
      catch
        case t: Throwable =>
          logger.warn(
            s"COMMIT at close failed for a stamped write (result already streamed): ${t.getMessage}"
          )
    underlying.close(closeReadSource)

  override protected def readSchema(): org.apache.arrow.vector.types.pojo.Schema =
    underlying.getVectorSchemaRoot.getSchema

  override protected def closeReadSource(): Unit = ()
