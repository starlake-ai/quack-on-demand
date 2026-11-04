package ai.starlake.quack.boot

import ai.starlake.quack.BranchingConfig
import ai.starlake.quack.edge.adapter.{
  ListFailure,
  MessageType,
  NodeLoadTracker,
  QuackError,
  QuackHttpAdapter,
  QuackHttpClient,
  QuackNativeBridge,
  QuackProtocol,
  QuackResponse,
  QuackTestFixtures,
  QuackTransport,
  QuackWireError,
  TestArrow
}
import ai.starlake.quack.edge.authz.NodeKeys
import ai.starlake.quack.model.{NodeSpec, PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.branch.{MergeDecision, MergeExecutor}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.circe.Json
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.net.{ServerSocket, URI}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.{
  ConcurrentLinkedQueue,
  CountDownLatch,
  Executors,
  ThreadFactory,
  TimeUnit
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The merge node bracket, `BranchWiring.mergeExecutor`: what the merge and the proposal analysis
  * rely on when they send several stages to one ephemeral node.
  *
  * Most cases run it over a fake backend (nodes that start and stop on command) and a fake native
  * client. The bound and the happy path run it over the real client on a scripted wire
  * ([[ScriptedWire]]), because only the real client's send is uncancelable: a fake that answers
  * `IO.never` cannot tell a bound that holds from one that waits for the send.
  */
class BranchWiringMergeExecutorSpec extends AnyFlatSpec with Matchers:

  private val cfg  = BranchingConfig(mergeTimeoutSec = 1, nodeReadyTimeoutSec = 1)
  private val spec = NodeSpec(PoolKey("t", "d", "p"), "merge-1", Role.Dual, Map.empty, Map.empty)

  /** The longest a held statement waits for its gate. Only a safety net: each case releases the
    * gate itself, so a wire still held after this long belongs to a failed run, and ends instead of
    * hanging it.
    */
  private val GateSafetyNet = 10.seconds

  /** What `BranchWiring` decodes a result up to (its private `MergeNodeMaxRows`). */
  private val RowCap = 1000

  private final class FakeBackend(
      port: Int,
      spawnFails: Boolean = false,
      alive: Boolean = true,
      stopFails: Option[String] = None,
      onStart: () => Unit = () => ()
  ) extends QuackBackend:
    val starts = new AtomicInteger(0)
    val stops  = new AtomicInteger(0)

    /** One uninterruptible step, as a spawn is: a cancel is only seen once it has returned. */
    def start(s: NodeSpec): IO[RunningNode] =
      if spawnFails then IO.raiseError(new RuntimeException("no capacity"))
      else
        IO.blocking {
          onStart()
          starts.incrementAndGet()
          RunningNode(
            s.nodeId,
            s.poolKey,
            s.role,
            "127.0.0.1",
            port,
            "tok",
            Some(1L),
            None,
            Instant.EPOCH
          )
        }
    def stop(key: PoolKey, nodeId: String): IO[Unit] =
      IO(stops.incrementAndGet()) *>
        stopFails.fold(IO.unit)(m => IO.raiseError(new RuntimeException(m)))
    def isAlive(nodeId: String): Boolean          = alive
    def discoverExisting(): IO[List[RunningNode]] = IO.pure(Nil)
    def cleanup(): IO[Unit]                       = IO.unit

  private final class FakeClient(answer: String => IO[QuackResponse])
      extends QuackHttpClient(TestArrow.sharedAllocator):
    val sent = ListBuffer.empty[String]
    override def query(
        endpoint: String,
        token: String,
        sql: String,
        session: Option[String],
        authorize: () => String
    ): IO[QuackResponse] =
      IO { sent.synchronized(sent += sql); authorize() } *> answer(sql)
    override def queryAll(
        endpoint: String,
        token: String,
        authorize: () => String,
        statements: List[String]
    ): IO[Either[ListFailure, Unit]] =
      statements.zipWithIndex.foldLeft(IO.pure[Either[ListFailure, Unit]](Right(()))) {
        case (done, (sql, entry)) =>
          done.flatMap {
            case Left(failure) => IO.pure(Left(failure))
            case Right(_)      =>
              IO { sent.synchronized(sent += sql); authorize() } *> answer(sql).flatMap {
                case QuackResponse.Ok(_, _, close) => IO(close()).as(Right(()))
                case QuackResponse.Failed(err, _)  => IO.pure(Left(ListFailure(Some(entry), err)))
              }
          }
      }

  /** A node's side of the wire as the real protocol sees it: every message is answered at once
    * except the statement's PREPARE, which is `statement` (a session handle's PREPARE and the
    * connection's messages are answered apart). Counts what arrived.
    */
  private final class ScriptedWire(statement: IO[Array[Byte]]) extends QuackTransport:
    private val seen                                       = new ConcurrentLinkedQueue[Int]()
    val statementReturned                                  = new AtomicBoolean(false)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO.defer {
      val t = QuackNativeBridge.parseMessageType(body)
      seen.add(t)
      if t == MessageType.PrepareRequest.wireOrdinal then
        if isHandle(body) then IO(preludeOk)
        else statement.flatTap(_ => IO(statementReturned.set(true)))
      else IO(QuackTestFixtures.serializeSampleConnectionResponse("conn-merge-spec"))
    }
    def count(m: MessageType): Int = seen.asScala.count(_ == m.wireOrdinal)

  /** A wire whose answer to a statement's PREPARE depends on the statement: the node's side of a
    * list. Keeps every statement that arrived and counts what else did, in order. `handle` answers
    * the nth session handle.
    */
  private final class ListWire(
      answer: String => IO[Array[Byte]],
      handle: Int => IO[Array[Byte]] = _ => IO(preludeOk)
  ) extends QuackTransport:
    private val handles                                    = new AtomicInteger(0)
    private val seen                                       = new ConcurrentLinkedQueue[Int]()
    val statements                                         = new ConcurrentLinkedQueue[String]()
    val steps                                              = new ConcurrentLinkedQueue[String]()
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO.defer {
      val t = QuackNativeBridge.parseMessageType(body)
      seen.add(t)
      if t == MessageType.PrepareRequest.wireOrdinal then
        if isHandle(body) then
          steps.add("handle")
          handle(handles.incrementAndGet())
        else
          val text = new String(body, StandardCharsets.ISO_8859_1)
          statements.add(text)
          steps.add("statement")
          answer(text)
      else
        if t == MessageType.ConnectionRequest.wireOrdinal then steps.add("connect")
        if t == MessageType.DisconnectMessage.wireOrdinal then steps.add("disconnect")
        IO(QuackTestFixtures.serializeSampleConnectionResponse("conn-merge-spec"))
    }
    def count(m: MessageType): Int = seen.asScala.count(_ == m.wireOrdinal)

  private def isHandle(body: Array[Byte]): Boolean =
    new String(body, StandardCharsets.ISO_8859_1).contains("CALL system.main.qod_session(")

  private lazy val preludeOk: Array[Byte] =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.ONE, false, false)

  /** One row, one INTEGER column: 42. */
  private lazy val prepareOk: Array[Byte] =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.TWO, false, true)

  private def realClient(allocator: RootAllocator, wire: QuackTransport) =
    new QuackHttpClient(
      allocator,
      protocolFactory = Some(alloc => new QuackProtocol(wire, alloc))
    )

  private def executorOver(backend: QuackBackend, client: QuackHttpClient) =
    BranchWiring.mergeExecutor(
      cfg,
      backend,
      new QuackHttpAdapter(client, new NodeLoadTracker, NodeKeys.ephemeral())
    )

  /** A port with a listener on it, which is all the readiness wait asks of a node. */
  private def listening[A](body: Int => A): A =
    val server = new ServerSocket(0)
    try body(server.getLocalPort)
    finally server.close()

  private def numbers(rows: List[List[Json]]) = rows.map(_.map(_.asNumber.flatMap(_.toLong)))

  /** Polls `cond` until it holds or `within` elapses; true iff it held. */
  private def eventually(within: FiniteDuration)(cond: => Boolean): Boolean =
    val deadline = System.nanoTime() + within.toNanos
    var ok       = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(25)
      ok = cond
    ok

  /** A runtime whose compute and blocking threads are named apart, so a case can tell which of the
    * two ran a call (`IO.blocking` shifts to the blocking pool of a runtime that is not the work
    * stealing one).
    */
  private def withNamedRuntime[A](body: IORuntime => A): A =
    def named(prefix: String): ThreadFactory =
      val n = new AtomicInteger(0)
      r =>
        val t = new Thread(r, s"$prefix-${n.incrementAndGet()}")
        t.setDaemon(true)
        t
    val compute                    = Executors.newFixedThreadPool(2, named("probe-compute"))
    val blocking                   = Executors.newCachedThreadPool(named("probe-blocking"))
    val (scheduler, stopScheduler) = Scheduler.createDefaultScheduler()
    val runtime                    = IORuntime(
      ExecutionContext.fromExecutor(compute),
      ExecutionContext.fromExecutor(blocking),
      scheduler,
      () => (),
      IORuntimeConfig()
    )
    try body(runtime)
    finally
      stopScheduler()
      compute.shutdown()
      blocking.shutdown()

  /** Runs `body` over the real client on a wire whose statement PREPARE is held, uninterruptibly as
    * a JDK send to a hung node is, until `body` releases the gate. Whatever `body` does, the gate
    * is released at the end and the abandoned send is waited for before the allocator is closed, so
    * a failed case reports its own failure and not a leak.
    */
  private def overHungWire(
      body: (RootAllocator, ScriptedWire, QuackHttpClient, CountDownLatch) => Unit
  ): Unit =
    val allocator = new RootAllocator()
    val gate      = new CountDownLatch(1)
    try
      val wire = new ScriptedWire(
        IO.blocking {
          gate.await(GateSafetyNet.toSeconds, TimeUnit.SECONDS)
          prepareOk
        }
      )
      body(allocator, wire, realClient(allocator, wire), gate)
    finally
      gate.countDown()
      eventually(10.seconds)(allocator.getAllocatedMemory == 0L)
      allocator.close()

  "withNode" should "run several stages on one node, then stop it once" in
    listening { port =>
      val backend = new FakeBackend(port)
      val closes  = new AtomicInteger(0)
      val client  = new FakeClient(sql =>
        IO.pure(
          if sql.startsWith("SELECT") then
            QuackResponse.Ok(
              TestArrow.readerFor("SELECT 7::BIGINT AS a, 9::BIGINT AS b"),
              1L,
              () => { closes.incrementAndGet(); () }
            )
          else QuackResponse.Ok(TestArrow.emptyReader(), 1L, () => { closes.incrementAndGet(); () })
        )
      )
      val out = executorOver(backend, client)
        .withNode(spec) { node =>
          (for
            _    <- EitherT(node.exec(List("CREATE TABLE x (i INT)")))
            rows <- EitherT(node.rows("SELECT 7, 9"))
            _    <- EitherT(node.exec(List("DROP TABLE x")))
          yield rows).value
        }
        .unsafeRunSync()
      out.map(numbers) shouldBe Right(List(List(Some(7L), Some(9L))))
      client.sent.toList shouldBe List("CREATE TABLE x (i INT)", "SELECT 7, 9", "DROP TABLE x")
      closes.get shouldBe 3
      backend.starts.get shouldBe 1
      backend.stops.get shouldBe 1
    }

  it should "stop the node when the body answers Left, and pass that Left through" in
    listening { port =>
      val backend = new FakeBackend(port)
      val out     = executorOver(backend, new FakeClient(_ => IO.never))
        .withNode[Unit](spec)(_ => IO.pure(Left("boom")))
        .unsafeRunSync()
      out shouldBe Left("boom")
      backend.stops.get shouldBe 1
    }

  it should "stop the node when the body raises" in
    listening { port =>
      val backend = new FakeBackend(port)
      val out     = executorOver(backend, new FakeClient(_ => IO.never))
        .withNode[Unit](spec)(_ => IO.raiseError(new RuntimeException("x")))
        .attempt
        .unsafeRunSync()
      out.left.toOption.map(_.getMessage) shouldBe Some("x")
      backend.stops.get shouldBe 1
    }

  it should "not run the body when the spawn fails, and stop nothing" in {
    val backend = new FakeBackend(1, spawnFails = true)
    var ran     = false
    val out     = executorOver(backend, new FakeClient(_ => IO.never))
      .withNode[Unit](spec)(_ => IO { ran = true; Right(()) })
      .unsafeRunSync()
    out shouldBe Left("merge node spawn failed: no capacity")
    ran shouldBe false
    backend.stops.get shouldBe 0
  }

  it should "not run the body when the node never accepts connections, and stop it" in {
    val backend = new FakeBackend(1, alive = false)
    var ran     = false
    val out     = executorOver(backend, new FakeClient(_ => IO.never))
      .withNode[Unit](spec)(_ => IO { ran = true; Right(()) })
      .unsafeRunSync()
    out shouldBe Left("merge node merge-1 did not accept connections within 1s")
    ran shouldBe false
    backend.stops.get shouldBe 1
  }

  it should "stop a node whose start returned after the caller was cancelled" in
    listening { port =>
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val backend = new FakeBackend(
        port,
        onStart = () =>
          entered.countDown()
          release.await(10, TimeUnit.SECONDS): Unit
      )
      var ran   = false
      val fiber = executorOver(backend, new FakeClient(_ => IO.never))
        .withNode[Unit](spec)(_ => IO { ran = true; Right(()) })
        .start
        .unsafeRunSync()
      entered.await(10, TimeUnit.SECONDS) shouldBe true
      // The cancel is requested while the spawn is still running, and seen once it has returned.
      fiber.cancel.start.unsafeRunSync()
      Thread.sleep(300)
      release.countDown()
      fiber.join.unsafeRunSync().isCanceled shouldBe true
      ran shouldBe false
      backend.starts.get shouldBe 1
      backend.stops.get shouldBe 1
    }

  it should "log a node that could not be stopped at ERROR with its id, and still answer" in
    listening { port =>
      val backend  = new FakeBackend(port, stopFails = Some("api server down"))
      val log      = LoggerFactory.getLogger(BranchWiring.getClass).asInstanceOf[Logger]
      val appender = new ListAppender[ILoggingEvent]
      val additive = log.isAdditive
      appender.start()
      log.addAppender(appender)
      // The ERROR line stays out of the suite output: no hand-off to the root appender.
      log.setAdditive(false)
      val out =
        try
          executorOver(backend, new FakeClient(_ => IO.never))
            .withNode(spec)(_ => IO.pure(Right(7)))
            .unsafeRunSync()
        finally
          log.setAdditive(additive)
          log.detachAppender(appender)
      out shouldBe Right(7)
      backend.stops.get shouldBe 1
      val errors = appender.list.asScala.toList.filter(_.getLevel == Level.ERROR)
      errors.map(_.getFormattedMessage) match
        case List(line) =>
          line should include("merge-1")
          line should include("api server down")
        case other => fail(s"expected one ERROR line, got $other")
    }

  it should "answer a failed send with the engine's text, for exec and for rows" in
    listening { port =>
      val backend              = new FakeBackend(port)
      def run(err: QuackError) =
        executorOver(backend, new FakeClient(_ => IO.pure(QuackResponse.Failed(err, 1L))))
          .withNode(spec) { node =>
            for
              e <- node.exec(List("S"))
              r <- node.rows("S")
            yield Right((e, r))
          }
          .unsafeRunSync()
      run(QuackError.Transient("conflict: boom")) shouldBe
        Right((Left("conflict: boom"), Left("conflict: boom")))
      run(QuackError.Permanent("bad sql")) shouldBe
        Right((Left("bad sql"), Left("bad sql")))
      // An engine error can hold the word too (a lock timeout): it is not a call that ran out of
      // time, which is what a consumer tests for by prefix.
      val lock = run(QuackError.Permanent("IO Error: lock timeout on the catalog"))
      lock shouldBe Right(
        (
          Left("IO Error: lock timeout on the catalog"),
          Left("IO Error: lock timeout on the catalog")
        )
      )
      lock.toOption.get._1.left.toOption.get.startsWith(MergeExecutor.TimedOut) shouldBe false
    }

  it should "answer a result that cannot be read as a value, and still close it" in
    listening { port =>
      val backend = new FakeBackend(port)
      val closes  = new AtomicInteger(0)
      val garbage = new ArrowStreamReader(
        new ByteArrayInputStream(Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)),
        TestArrow.sharedAllocator
      )
      val out = executorOver(
        backend,
        new FakeClient(_ =>
          IO.pure(QuackResponse.Ok(garbage, 1L, () => { closes.incrementAndGet(); () }))
        )
      ).withNode(spec)(_.rows("SELECT 1")).unsafeRunSync()
      out.left.toOption.exists(_.startsWith("reading the result: ")) shouldBe true
      closes.get shouldBe 1
      backend.stops.get shouldBe 1
    }

  it should "answer Left for a result cut at the row cap, and the rows when it fits exactly" in
    listening { port =>
      def rowsOf(n: Int) =
        executorOver(
          new FakeBackend(port),
          new FakeClient(_ =>
            IO(
              QuackResponse.Ok(
                TestArrow.readerFor(s"SELECT i FROM range($n) t(i)"),
                1L,
                () => ()
              )
            )
          )
        ).withNode(spec)(_.rows("S")).unsafeRunSync()
      rowsOf(RowCap).map(_.size) shouldBe Right(RowCap)
      val cut = rowsOf(RowCap + 1)
      cut.isLeft shouldBe true
      cut.left.toOption.get should include(s"more than $RowCap rows")
    }

  it should "close a result off the compute threads" in {
    val closedOn = new ConcurrentLinkedQueue[String]()
    val client   = new FakeClient(_ =>
      IO {
        val reader = TestArrow.readerFor("SELECT 1::BIGINT AS a")
        QuackResponse.Ok(
          reader,
          1L,
          () =>
            closedOn.add(Thread.currentThread().getName)
            reader.close()
        )
      }
    )
    withNamedRuntime { runtime =>
      listening { port =>
        val out = executorOver(new FakeBackend(port), client)
          .withNode(spec)(_.rows("S"))
          .unsafeRunSync()(using runtime)
        out.isRight shouldBe true
      }
    }
    // A close is a blocking POST (the DISCONNECT): it must not pin a compute thread.
    closedOn.asScala.toList.map(_.split("-").take(2).mkString("-")) shouldBe
      List("probe-blocking")
  }

  "a node that hangs" should "be answered with Left at the bound, with its send still running" in
    overHungWire { (allocator, wire, client, gate) =>
      listening { port =>
        val backend = new FakeBackend(port)
        val began   = System.nanoTime()
        val out = executorOver(backend, client).withNode(spec)(_.exec(List("S"))).unsafeRunSync()
        val elapsed = (System.nanoTime() - began).nanos.toMillis
        val reason  = out.left.toOption.getOrElse(fail(s"expected a Left, got $out"))
        reason should startWith(MergeExecutor.TimedOut)
        // The reason tells the operator the way out, with the bound that was hit.
        reason should include("within 1s")
        reason should include("QOD_BRANCH_MERGE_TIMEOUT_SEC")
        // The answer came while the POST was still held: the bound did not wait for the send. The
        // gate is the test's to release, so this holds on any machine, however slow.
        wire.statementReturned.get shouldBe false
        // And it did not come before the bound.
        elapsed should be >= 900L
        backend.stops.get shouldBe 1
        // Released, the abandoned send ends, and closes its connection and its result.
        gate.countDown()
        eventually(10.seconds)(wire.count(MessageType.DisconnectMessage) == 1) shouldBe true
        eventually(5.seconds)(allocator.getAllocatedMemory == 0L) shouldBe true
      }
    }

  it should "bound rows the same way" in
    overHungWire { (allocator, wire, client, gate) =>
      listening { port =>
        val out = executorOver(new FakeBackend(port), client)
          .withNode(spec)(_.rows("SELECT 1"))
          .unsafeRunSync()
        out.left.toOption.exists(_.startsWith(MergeExecutor.TimedOut)) shouldBe true
        wire.statementReturned.get shouldBe false
        gate.countDown()
        eventually(10.seconds)(wire.count(MessageType.DisconnectMessage) == 1) shouldBe true
        eventually(5.seconds)(allocator.getAllocatedMemory == 0L) shouldBe true
      }
    }

  "a list of statements" should "go on one connection, each after a handle of its own, and close it" in {
    val allocator = new RootAllocator()
    try
      val wire = new ListWire(_ => IO(preludeOk))
      listening { port =>
        val out = executorOver(new FakeBackend(port), realClient(allocator, wire))
          .withNode(spec)(_.exec(List("BEGIN", "UPDATE x SET i = 1", "COMMIT")))
          .unsafeRunSync()
        out shouldBe Right(())
        wire.steps.asScala.toList shouldBe
          List(
            "connect",
            "handle",
            "statement",
            "handle",
            "statement",
            "handle",
            "statement",
            "disconnect"
          )
        allocator.getAllocatedMemory shouldBe 0L
      }
    finally allocator.close()
  }

  it should "stop at the first failure, with the engine's text, and close the connection" in {
    val allocator = new RootAllocator()
    try
      val wire = new ListWire(text =>
        IO(
          if text.contains("UPDATE") then
            QuackTestFixtures.serializeSampleErrorResponse("Transaction conflict: no")
          else preludeOk
        )
      )
      listening { port =>
        val out = executorOver(new FakeBackend(port), realClient(allocator, wire))
          .withNode(spec)(_.exec(List("BEGIN", "UPDATE x SET i = 1", "COMMIT")))
          .unsafeRunSync()
        out.left.toOption.get should include("Transaction conflict: no")
        wire.steps.asScala.toList shouldBe
          List("connect", "handle", "statement", "handle", "statement", "disconnect")
        wire.statements.asScala.exists(_.contains("COMMIT")) shouldBe false
        allocator.getAllocatedMemory shouldBe 0L
      }
    finally allocator.close()
  }

  it should "send no COMMIT after a timeout, and roll back: the bound stops the list" in {
    val allocator = new RootAllocator()
    val gate      = new CountDownLatch(1)
    try
      val wire = new ListWire(text =>
        if text.contains("UPDATE") then
          IO.blocking {
            gate.await(GateSafetyNet.toSeconds, TimeUnit.SECONDS)
            preludeOk
          }
        else IO(preludeOk)
      )
      listening { port =>
        val backend = new FakeBackend(port)
        val out     = executorOver(backend, realClient(allocator, wire))
          .withNode(spec)(_.exec(List("BEGIN", "UPDATE x SET i = 1", "COMMIT")))
          .unsafeRunSync()
        out.left.toOption.exists(_.startsWith(MergeExecutor.TimedOut)) shouldBe true
        backend.stops.get shouldBe 1
        // The statement in flight answers long after the timeout was reported: nothing follows it
        // but the DISCONNECT, which rolls its transaction back.
        gate.countDown()
        eventually(10.seconds)(wire.count(MessageType.DisconnectMessage) == 1) shouldBe true
        Thread.sleep(300)
        wire.statements.asScala.exists(_.contains("COMMIT")) shouldBe false
        wire.steps.asScala.toList shouldBe
          List("connect", "handle", "statement", "handle", "statement", "disconnect")
        eventually(5.seconds)(allocator.getAllocatedMemory == 0L) shouldBe true
      }
    finally
      gate.countDown()
      eventually(10.seconds)(allocator.getAllocatedMemory == 0L)
      allocator.close()
  }

  /** An Apply's shape over the real client on `wire`: what `exec` answers. */
  private def applyOver(wire: ListWire): Either[String, Unit] =
    val allocator = new RootAllocator()
    try
      listening { port =>
        executorOver(new FakeBackend(port), realClient(allocator, wire))
          .withNode(spec)(_.exec(List("BEGIN", "UPDATE x SET i = 1", "COMMIT")))
          .unsafeRunSync()
      }
    finally allocator.close()

  private def isCommit(text: String) = text.contains("COMMIT")

  it should "read a lost answer to COMMIT as an unknown outcome, as it reads a send that timed out" in
    // A 5xx, and the JDK client's own failure: neither says what the node did with the COMMIT.
    List(
      QuackWireError.Transient("HTTP 503 from Quack node\nthe second line"),
      new java.io.IOException("HTTP/1.1 header parser received no bytes\nthe second line")
    ).foreach { lost =>
      val wire = new ListWire(text => if isCommit(text) then IO.raiseError(lost) else IO(preludeOk))
      val first = lost.getMessage.linesIterator.next()
      applyOver(wire) shouldBe Left(
        s"${MergeExecutor.TimedOut}the answer to COMMIT was lost, so it may have landed: $first"
      )
      wire.steps.asScala.toList.takeRight(3) shouldBe List("handle", "statement", "disconnect")
    }

  it should "keep a transport failure before COMMIT definite: no COMMIT went out" in
    List(
      QuackWireError.Transient("HTTP 503 from Quack node"),
      new java.io.IOException("HTTP/1.1 header parser received no bytes")
    ).foreach { lost =>
      val wire =
        new ListWire(text => if text.contains("UPDATE") then IO.raiseError(lost) else IO(preludeOk))
      applyOver(wire) shouldBe Left(lost.getMessage)
      wire.statements.asScala.exists(isCommit) shouldBe false
      wire.steps.asScala.toList.last shouldBe "disconnect"
    }

  it should "keep a failed handle before COMMIT definite: COMMIT itself never went out" in {
    val refused =
      QuackTestFixtures.serializeSampleErrorResponse("qod-node: session handle refused: expired")
    // Entries 0 and 1 went out; the third handle is COMMIT's.
    List[Int => IO[Array[Byte]]](
      {
        case 3 => IO(refused)
        case _ => IO(preludeOk)
      },
      {
        case 3 => IO.raiseError(new java.io.IOException("connection reset"))
        case _ => IO(preludeOk)
      }
    ).foreach { third =>
      val wire = new ListWire(_ => IO(preludeOk), third)
      val out  = applyOver(wire)
      out.left.toOption.exists(_.startsWith(MergeExecutor.TimedOut)) shouldBe false
      out.isLeft shouldBe true
      wire.statements.asScala.exists(isCommit) shouldBe false
      wire.steps.asScala.toList.last shouldBe "disconnect"
    }
  }

  it should "keep the node's own answer on COMMIT definite: a fence refusal stays retryable" in {
    def answered(text: String) =
      val wire = new ListWire(sql =>
        IO(
          if isCommit(sql) then QuackTestFixtures.serializeSampleErrorResponse(text) else preludeOk
        )
      )
      applyOver(wire).left.toOption.get
    val refused = answered("qod merge fence: a merged table moved past snapshot 3")
    refused should not startWith MergeExecutor.TimedOut
    refused should include("qod merge fence: a merged table moved past snapshot 3")
    MergeDecision.mainMoved(refused) shouldBe true
    val conflict = answered("Transaction conflict: attempting to delete from a deleted file")
    conflict should not startWith MergeExecutor.TimedOut
    conflict should include("Transaction conflict")
    // An HTTP answer from the node refuses the request: nothing ran.
    val http = new ListWire(sql =>
      if isCommit(sql) then IO.raiseError(QuackWireError.Permanent("HTTP 401 from Quack node"))
      else IO(preludeOk)
    )
    applyOver(http) shouldBe Left("HTTP 401 from Quack node")
  }

  it should "hold the whole list to the bound, not each statement" in {
    val allocator = new RootAllocator()
    try
      // Each statement answers well within the bound of 1 s, and the list does not.
      val wire = new ListWire(_ => IO.sleep(600.millis).as(preludeOk))
      listening { port =>
        val out = executorOver(new FakeBackend(port), realClient(allocator, wire))
          .withNode(spec)(_.exec(List("BEGIN", "UPDATE x SET i = 1", "COMMIT")))
          .unsafeRunSync()
        out.left.toOption.exists(_.startsWith(MergeExecutor.TimedOut)) shouldBe true
        eventually(10.seconds)(wire.count(MessageType.DisconnectMessage) == 1) shouldBe true
        Thread.sleep(300)
        wire.statements.asScala.exists(_.contains("COMMIT")) shouldBe false
        eventually(5.seconds)(allocator.getAllocatedMemory == 0L) shouldBe true
      }
    finally allocator.close()
  }

  "over the real client" should "close the connection after exec, and read rows off the wire" in {
    val allocator = new RootAllocator()
    try
      val wire = new ScriptedWire(IO(prepareOk))
      listening { port =>
        val out = executorOver(new FakeBackend(port), realClient(allocator, wire))
          .withNode(spec) { node =>
            (for
              _    <- EitherT(node.exec(List("CREATE TABLE x (i INT)")))
              rows <- EitherT(node.rows("SELECT 42"))
            yield rows).value
          }
          .unsafeRunSync()
        out.map(numbers) shouldBe Right(List(List(Some(42L))))
        // Each call opened a connection and closed it: exec closes its result unread.
        wire.count(MessageType.ConnectionRequest) shouldBe 2
        wire.count(MessageType.DisconnectMessage) shouldBe 2
        allocator.getAllocatedMemory shouldBe 0L
      }
    finally allocator.close()
  }
