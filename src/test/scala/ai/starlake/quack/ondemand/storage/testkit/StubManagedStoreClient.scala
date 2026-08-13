package ai.starlake.quack.ondemand.storage.testkit

import ai.starlake.quack.ondemand.storage.ManagedStoreClient

import scala.collection.concurrent.TrieMap

/** In-memory [[ManagedStoreClient]] test double. No network, no AWS SDK. Consumed by the specs that
  * drive prefix listing / batch deletion (Tasks 3-5).
  */
final class StubManagedStoreClient extends ManagedStoreClient:
  private val keys = TrieMap.empty[String, Unit]

  /** When set, the NEXT `deleteBatch` call returns `Left(msg)` instead of deleting, then the flag
    * resets to `None` so later calls succeed again (single-shot failure injection).
    */
  var failNextDelete: Option[String] = None

  /** When set, the NEXT `listPrefix` call returns `Left(msg)` instead of listing, then the flag
    * resets to `None` so later calls succeed again (single-shot failure injection).
    */
  var failNextList: Option[String] = None

  var listPrefixCalls: Int  = 0
  var deleteBatchCalls: Int = 0

  /** Seed `count` synthetic keys under `prefix` (e.g. `prefix0`, `prefix1`, ...). */
  def seed(prefix: String, count: Int): Unit =
    (0 until count).foreach(i => keys.put(s"$prefix$i", ()))

  /** Insert a single key. */
  def insert(key: String): Unit = keys.put(key, ())

  /** Snapshot of every key currently held, for assertions. */
  def allKeys: List[String] = keys.keys.toList

  def ensureBucket(): Either[String, Unit] = Right(())

  def listPrefix(prefix: String, max: Int): Either[String, List[String]] =
    listPrefixCalls += 1
    failNextList match
      case Some(msg) =>
        failNextList = None
        Left(msg)
      case None =>
        Right(keys.keys.filter(_.startsWith(prefix)).toList.sorted.take(max))

  def deleteBatch(ks: List[String]): Either[String, Unit] =
    deleteBatchCalls += 1
    failNextDelete match
      case Some(msg) =>
        failNextDelete = None
        Left(msg)
      case None =>
        ks.foreach(keys.remove)
        Right(())
