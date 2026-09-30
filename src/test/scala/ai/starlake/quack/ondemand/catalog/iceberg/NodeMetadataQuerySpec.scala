package ai.starlake.quack.ondemand.catalog.iceberg

import ai.starlake.quack.edge.adapter.{QuackError, QuackResponse}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.federation.iceberg.AttachStatusRegistry
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{IntVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

/** Covers [[NodeMetadataQuery]] (with stub collaborators, so the routing/timeout/decode/redaction
  * logic is exercised without a real node or Arrow-over-HTTP call) plus
  * [[AttachStatusRegistry.isAttached]], the small accessor this task adds alongside it.
  */
class NodeMetadataQuerySpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_lake", "reader")

  private def node(id: String): RunningNode =
    RunningNode(
      nodeId = id,
      poolKey = poolKey,
      role = Role.ReadOnly,
      host = "127.0.0.1",
      port = 21900,
      token = "tok",
      pid = None,
      podName = None,
      startedAt = Instant.EPOCH
    )

  private val idField = Field.nullable("id", new ArrowType.Int(32, true))
  private val schema  = new Schema(java.util.List.of(idField))

  /** Real single-row Arrow IPC stream (same shape [[ArrowRowsDecoderSpec]] uses), so `Ok` responses
    * decode through the real `ArrowRowsDecoder`, not a mock.
    */
  private def arrowReaderOf(allocator: RootAllocator): ArrowStreamReader =
    val writeAllocator = new RootAllocator()
    val root           = VectorSchemaRoot.create(schema, writeAllocator)
    val out            = new ByteArrayOutputStream()
    try
      val writer = new ArrowStreamWriter(root, null, out)
      try
        writer.start()
        val idVec = root.getVector("id").asInstanceOf[IntVector]
        root.allocateNew()
        idVec.setSafe(0, 42)
        root.setRowCount(1)
        writer.writeBatch()
        writer.end()
      finally writer.close()
    finally
      root.close()
      writeAllocator.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  private def failingSend(err: QuackError): (RunningNode, String) => IO[QuackResponse] =
    (_, _) => IO.pure(QuackResponse.Failed(err, 0L))

  private def newQuery(
      readPool: (String, String) => Option[PoolKey] = (_, _) => Some(poolKey),
      readNodes: PoolKey => List[RunningNode] = _ => Nil,
      isAttached: (RunningNode, String) => Boolean = (_, _) => false,
      attachSummary: (String, Set[String]) => Option[String] = (_, _) => None,
      send: (RunningNode, String) => IO[QuackResponse] = (_, _) =>
        IO.raiseError(new RuntimeException("unused")),
      timeoutSec: Int = 5,
      maxRows: Int = 100
  ): NodeMetadataQuery =
    new NodeMetadataQuery(readPool, readNodes, isAttached, attachSummary, send, timeoutSec, maxRows)

  "NodeMetadataQuery.run" should "return Left(NoPool) when the tenant-db has no pool" in {
    val q = newQuery(readPool = (_, _) => None)
    q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync() shouldBe Left(MetadataFailure.NoPool)
  }

  it should "return Left(NoRunningNode) when the pool has no read nodes" in {
    val q = newQuery(readNodes = _ => Nil)
    q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync() shouldBe Left(
      MetadataFailure.NoRunningNode
    )
  }

  it should "send the SQL to the first node the alias is attached on" in {
    val n1  = node("n1")
    val n2  = node("n2")
    val hit = new AtomicInteger(0)
    val q   = newQuery(
      readNodes = _ => List(n1, n2),
      isAttached = (n, _) => n.nodeId == "n2",
      send = (n, sql) =>
        if n.nodeId == "n2" && sql == "SELECT 1" then
          hit.incrementAndGet()
          IO.pure(QuackResponse.Failed(QuackError.Permanent("boom"), 0L))
        else IO.raiseError(new RuntimeException(s"unexpected send to ${n.nodeId}"))
    )
    q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync() shouldBe Left(
      MetadataFailure.Remote("Permanent(boom)")
    )
    hit.get() shouldBe 1
  }

  it should "return Left(NotAttached(summary)) and never call send when no node is attached" in {
    val n1   = node("n1")
    val n2   = node("n2")
    var sent = false
    val q    = newQuery(
      readNodes = _ => List(n1, n2),
      isAttached = (_, _) => false,
      attachSummary = (alias, nodeIds) =>
        if alias == "sales" && nodeIds == Set("n1", "n2") then Some("unknown") else None,
      send = (_, _) =>
        sent = true
        IO.raiseError(new RuntimeException("must not be called"))
    )
    q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync() shouldBe Left(
      MetadataFailure.NotAttached(Some("unknown"))
    )
    sent shouldBe false
  }

  it should "redact a QuackResponse.Failed error through AttachErrorRedactor" in {
    val secretLooking =
      "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5" // 40+ base64-looking chars
    val q = newQuery(
      readNodes = _ => List(node("n1")),
      isAttached = (_, _) => true,
      send = failingSend(QuackError.Permanent(s"token exchange failed: $secretLooking"))
    )
    val result = q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync()
    result match
      case Left(MetadataFailure.Remote(message)) =>
        message should not include secretLooking
        message should include("[redacted]")
      case other => fail(s"expected Left(Remote(_)), got $other")
  }

  it should "return Left(TimedOut) when send never completes" in {
    val q = newQuery(
      readNodes = _ => List(node("n1")),
      isAttached = (_, _) => true,
      send = (_, _) => IO.never,
      timeoutSec = 1
    )
    q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync() shouldBe Left(
      MetadataFailure.TimedOut
    )
  }

  it should "decode rows from QuackResponse.Ok and close the reader exactly once" in {
    val allocator = new RootAllocator()
    val closes    = new AtomicInteger(0)
    try
      val reader = arrowReaderOf(allocator)
      val q      = newQuery(
        readNodes = _ => List(node("n1")),
        isAttached = (_, _) => true,
        send = (_, _) =>
          IO.pure(
            QuackResponse.Ok(
              reader,
              0L,
              () => {
                closes.incrementAndGet()
                reader.close()
              }
            )
          )
      )
      val result = q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync()
      result match
        case Right(rows) =>
          rows.size shouldBe 1
          rows.head.head.asNumber.flatMap(_.toInt) shouldBe Some(42)
        case other => fail(s"expected Right(_), got $other")
      closes.get() shouldBe 1
    finally allocator.close()
  }

  it should "return Left(Remote(_)) and still close the reader exactly once when decoding throws" in {
    val closes    = new AtomicInteger(0)
    val allocator = new RootAllocator()
    try
      // A reader whose getVectorSchemaRoot throws stands in for a decode failure: ArrowRowsDecoder
      // reads the schema before touching any batch, so this is enough to force the `finally`. A
      // decode failure is just as much a remote-node failure as a network error, so it must be
      // redacted and reported the same way -- not escape as a raw failed IO.
      val brokenReader = new ArrowStreamReader(
        new ByteArrayInputStream(Array.emptyByteArray),
        allocator
      ) {
        override def getVectorSchemaRoot: VectorSchemaRoot =
          throw new RuntimeException("decode boom")
      }
      val q = newQuery(
        readNodes = _ => List(node("n1")),
        isAttached = (_, _) => true,
        send = (_, _) => IO.pure(QuackResponse.Ok(brokenReader, 0L, () => closes.incrementAndGet()))
      )
      q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync() shouldBe Left(
        MetadataFailure.Remote("decode boom")
      )
      closes.get() shouldBe 1
    finally allocator.close()
  }

  it should "return Left(TimedOut) and close once when the response arrives but decoding blocks past the timeout" in {
    val closes    = new AtomicInteger(0)
    val allocator = new RootAllocator()
    try
      // No batch is ever read -- getVectorSchemaRoot alone blocks past timeoutSec, standing in for
      // a slow drain (e.g. a chained network reader stalling mid-stream). The decode must be
      // covered by the same timeout as the network call, not just the `send` itself.
      val slowReader = new ArrowStreamReader(
        new ByteArrayInputStream(Array.emptyByteArray),
        allocator
      ) {
        override def getVectorSchemaRoot: VectorSchemaRoot =
          Thread.sleep(1500)
          throw new RuntimeException("must not be reached: the timeout should win first")
      }
      val q = newQuery(
        readNodes = _ => List(node("n1")),
        isAttached = (_, _) => true,
        send = (_, _) => IO.pure(QuackResponse.Ok(slowReader, 0L, () => closes.incrementAndGet())),
        timeoutSec = 1
      )
      q.run("acme", "lake", "sales", "SELECT 1").unsafeRunSync() shouldBe Left(
        MetadataFailure.TimedOut
      )
      closes.get() shouldBe 1
    finally allocator.close()
  }

  it should "close the reader exactly once when cancelled right after send hands back Ok (regression: acquire must be masked against the flatMap boundary)" in {
    // Single-fiber deterministic version of the race `IO.uncancelable` + `poll` guards against:
    // `send` runs `IO.canceled` INSIDE its own `IO.uncancelable` region before handing back `Ok`.
    // `IO.canceled` only sets this fiber's cancellation flag -- masked, it cannot act on it yet --
    // so cancellation is provably already pending by the time `Ok` reaches the flatMap boundary,
    // with no dependence on scheduler ordering between two fibers (the earlier started/release
    // version this replaces relied on `fiber.cancel.start` running before `release.complete`
    // resumed the query fiber, which work stealing could reorder).
    val allocator = new RootAllocator()
    try
      val closes = new AtomicInteger(0)
      val reader = arrowReaderOf(allocator)
      val ok     = QuackResponse.Ok(
        reader,
        0L,
        () => {
          closes.incrementAndGet()
          reader.close()
        }
      )
      val q = newQuery(
        readNodes = _ => List(node("n1")),
        isAttached = (_, _) => true,
        send = (_, _) => IO.uncancelable(_ => IO.canceled *> IO.pure(ok)),
        timeoutSec = 30 // cancellation here is self-inflicted, not timeout-driven
      )
      val outcome = q.run("acme", "lake", "sales", "SELECT 1").start.flatMap(_.join).unsafeRunSync()
      closes.get() shouldBe 1
      outcome shouldBe a[cats.effect.kernel.Outcome.Canceled[IO, Throwable, ?]]
    finally allocator.close()
  }

  "AttachStatusRegistry.isAttached" should "be false before recordAttached, true after, case-insensitive, and false again after recordFailure" in {
    val registry = new AttachStatusRegistry()
    registry.isAttached("n1", 100L, "Sales") shouldBe false

    registry.recordAttached("n1", 100L, "Sales")
    registry.isAttached("n1", 100L, "Sales") shouldBe true
    registry.isAttached("n1", 100L, "sales") shouldBe true
    registry.isAttached("n1", 100L, "SALES") shouldBe true

    registry.recordFailure("n1", 100L, "Sales", "boom")
    registry.isAttached("n1", 100L, "Sales") shouldBe false
  }
