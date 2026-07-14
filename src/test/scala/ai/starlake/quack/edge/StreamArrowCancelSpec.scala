package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import org.apache.arrow.flight.FlightProducer.ServerStreamListener
import org.apache.arrow.vector.VectorSchemaRoot
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Pins streamArrow's client-cancel contract: when the peer cancels the stream mid-flight (DBeaver
  * closing a result set after its first fetch page, an ADBC reader closed early), the pump loop
  * must stop instead of reading the whole remaining result from the node and queueing it into a
  * dead gRPC stream (the source of netty's "Stream closed before write could take place" warnings).
  */
class StreamArrowCancelSpec extends AnyFlatSpec with Matchers:

  private def producer(): FlightProducerImpl =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          25000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(id: String)    = IO { n.remove(id); () }
      def isAlive(id: String) = n.contains(id)
      def discoverExisting()  = IO.pure(n.values.toList)
      def cleanup()           = IO(n.clear())
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    val client  = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        IO.pure(TestArrow.okResponse())
    val adapter = new QuackHttpAdapter(client, tracker)
    val router  = new FlightSqlRouter(sup, new SessionRegistry, tracker, adapter)
    new FlightProducerImpl(router)

  /** Counts putNext calls and total rows; flips to cancelled after `cancelAfter` batches. */
  private final class CountingListener(cancelAfter: Int = Int.MaxValue)
      extends ServerStreamListener:
    var putNextCount                                 = 0
    var rowsSeen                                     = 0L
    @volatile var completedCalled                    = false
    @volatile private var cancelled                  = false
    private var root: VectorSchemaRoot               = null
    override def start(root: VectorSchemaRoot): Unit = this.root = root
    override def start(
        root: VectorSchemaRoot,
        dict: org.apache.arrow.vector.dictionary.DictionaryProvider
    ): Unit = start(root)
    override def start(
        root: VectorSchemaRoot,
        dict: org.apache.arrow.vector.dictionary.DictionaryProvider,
        opts: org.apache.arrow.vector.ipc.message.IpcOption
    ): Unit = start(root)
    override def putNext(): Unit =
      putNextCount += 1
      rowsSeen += root.getRowCount
      if putNextCount >= cancelAfter then cancelled = true
    override def putNext(buf: org.apache.arrow.memory.ArrowBuf): Unit     = putNext()
    override def putMetadata(buf: org.apache.arrow.memory.ArrowBuf): Unit = ()
    override def error(t: Throwable): Unit                                = ()
    override def completed(): Unit                                        = completedCalled = true
    override def isReady(): Boolean                                       = true
    override def isCancelled(): Boolean                                   = cancelled
    override def setOnReadyHandler(handler: Runnable): Unit               = ()
    override def setUseZeroCopy(zeroCopy: Boolean): Unit                  = ()
    override def setOnCancelHandler(handler: Runnable): Unit              = ()

  private val multiBatchSql = "SELECT * FROM range(5000)"

  "streamArrow" should "stream every batch when the client never cancels" in:
    val p        = producer()
    val listener = new CountingListener()
    val reader   = TestArrow.readerFor(multiBatchSql)
    try p.streamArrow(reader, listener)
    finally reader.close()
    listener.rowsSeen shouldBe 5000L
    // Guards the fixture: the cancel test below is only meaningful multi-batch.
    listener.putNextCount should be >= 2
    listener.completedCalled shouldBe true

  it should "stop pumping batches once the client has cancelled the stream" in:
    val p        = producer()
    val listener = new CountingListener(cancelAfter = 1)
    val reader   = TestArrow.readerFor(multiBatchSql)
    try p.streamArrow(reader, listener)
    finally reader.close()
    listener.putNextCount shouldBe 1
    listener.rowsSeen should be < 5000L
