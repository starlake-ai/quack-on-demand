package ai.starlake.quack.edge.adapter

import org.apache.arrow.c.{ArrowArrayStream, Data}
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowReader

/** Bridges the native Arrow C-data ABI pointer returned by [[QuackNativeBridge.extractArrowStream]]
  * into an Arrow Java `ArrowReader`. The Arrow Java `Data.importArrayStream` takes ownership of the
  * underlying `ArrowArrayStream`: the C-side `release` callback runs when the returned reader is
  * closed, which deletes the `ChunkStreamHolder` (and with it the moved-out
  * `vector<unique_ptr<DataChunkWrapper>>`) on the native heap.
  *
  * The caller MUST close the returned reader exactly once. Failure to close leaks the holder;
  * double-close is harmless because the C-side `release` callback sets `release = nullptr` after
  * the first call, which the Arrow Java importer treats as already-released.
  */
object QuackArrowImport:

  /** Imports a native `ArrowArrayStream*` (raw pointer cast to `Long`) into an `ArrowReader`. The
    * `allocator` is used by the Arrow Java importer to back the imported `VectorSchemaRoot`; pass a
    * child allocator if you want strict lifetime tracking.
    */
  def importStream(allocator: BufferAllocator, nativePtr: Long): ArrowReader =
    val stream = ArrowArrayStream.wrap(nativePtr)
    Data.importArrayStream(allocator, stream)
