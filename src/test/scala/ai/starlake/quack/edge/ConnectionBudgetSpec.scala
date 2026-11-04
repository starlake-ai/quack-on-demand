package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What each listener's connections can hold before any credential, and how many of them a heap
  * takes. Everything is given the heap as a number: nothing here reads this JVM's.
  */
class ConnectionBudgetSpec extends AnyFlatSpec with Matchers:

  // The three listeners at their defaults: REST (8 KiB headers, 64 KiB public bodies), the Quack
  // front door (4 KiB, 64 KiB connect bodies) and the node verdict listener (4 KiB, 1 MiB bodies).
  private val rest        = ConnectionBudget.heldBytes(8192, 65536L, tls = false)
  private val quack       = ConnectionBudget.heldBytes(4096, 65536L, tls = false)
  private val quackTls    = ConnectionBudget.heldBytes(4096, 65536L, tls = true)
  private val listener    = ConnectionBudget.heldBytes(4096, 1048576L, tls = false)
  private val oneGiB      = 1L << 30
  private val fourGiB     = 4L << 30
  private val chartHeap   = fourGiB / 2 // -XX:MaxRAMPercentage=50 under a 4 GiB limit
  private val fiveMiB     = 5L << 20
  private val noMemoryFor = 1L << 20

  "heldBytes" should "be what a connection holds at each listener's defaults" in:
    rest shouldBe 825344L
    quack shouldBe 497664L
    quackTls shouldBe 666624L
    listener shouldBe 1480704L

  it should "count every stage a connection can be in, whatever it is doing" in:
    val held: (Int, Long, Boolean) => Long = ConnectionBudget.heldBytes
    // Ember parses the request line and the header block into objects, applies the limit to each
    // of the two, and keeps both for the whole request. REST's guard and Tapir drop leading empty
    // path segments, so a line at its limit reaches a body read as well.
    held(2048, 0L, false) - held(1024, 0L, false) shouldBe
      2L * 1024 * ConnectionBudget.HeapPerParsedByte
    // The body counts whole, and the rest of the first read (Ember keeps it) and the connection's
    // own state count whatever is sent.
    held(1024, 5000L, false) - held(1024, 1000L, false) shouldBe 4000L
    held(1024, 0L, false) - 2L * 1024 * ConnectionBudget.HeapPerParsedByte shouldBe
      ConnectionBudget.ConnectionBytes + BoundedBody.ReadBufferBytes

  it should "grow with each of its arguments, and with TLS" in:
    val held: (Int, Long, Boolean) => Long = ConnectionBudget.heldBytes
    held(2048, 65536L, false) should be < held(4096, 65536L, false)
    held(4096, 65536L, false) should be < held(8192, 65536L, false)
    held(4096, 65536L, false) should be < held(4096, 1048576L, false)
    held(4096, 1048576L, false) should be < held(4096, 8388608L, false)
    held(4096, 65536L, false) should be < held(4096, 65536L, true)
    ConnectionBudget.heldBytes(4096, 65536L, tls = true) - quack shouldBe ConnectionBudget.TlsBytes

  "fit" should "take what a fifth of the heap holds, at a heap of 1 GiB" in:
    ConnectionBudget.fit(1024, rest, oneGiB) shouldBe 260
    ConnectionBudget.fit(1024, quack, oneGiB) shouldBe 431
    ConnectionBudget.fit(1024, quackTls, oneGiB) shouldBe 322
    ConnectionBudget.fit(256, listener, oneGiB) shouldBe 145

  it should "leave each listener at most a fifth of the heap" in:
    for
      heap <- List(oneGiB, 2L << 30, fourGiB, 16L << 30)
      held <- List(rest, quack, quackTls, listener)
    do
      val taken = ConnectionBudget.fit(Int.MaxValue, held, heap)
      withClue(s"heap $heap, held $held: ")(taken.toLong * held should be <= heap / 5)

  it should "take what is configured on the chart's heap" in:
    ConnectionBudget.fit(1024, rest, chartHeap) shouldBe 520
    ConnectionBudget.fit(1024, quack, chartHeap) shouldBe 863
    ConnectionBudget.fit(256, listener, chartHeap) shouldBe 256

  it should "take what is configured on a heap of 4 GiB" in:
    ConnectionBudget.fit(1024, rest, fourGiB) shouldBe 1024
    ConnectionBudget.fit(1024, quack, fourGiB) shouldBe 1024
    ConnectionBudget.fit(256, listener, fourGiB) shouldBe 256

  it should "never take more than is configured" in:
    for
      configured <- List(1, 7, 256, 1024)
      heap       <- List(oneGiB, chartHeap, 1L << 40, Long.MaxValue)
      held       <- List(rest, quack, listener)
    do ConnectionBudget.fit(configured, held, heap) should be <= configured

  it should "take at least one connection, on a heap too small for one" in:
    ConnectionBudget.fit(1024, rest, noMemoryFor) shouldBe 1
    ConnectionBudget.fit(1024, listener, fiveMiB) shouldBe 1
    ConnectionBudget.fit(1024, rest, 0L) shouldBe 1

  it should "take what is configured on a heap of any size" in:
    ConnectionBudget.fit(1024, rest, Long.MaxValue) shouldBe 1024
    ConnectionBudget.fit(256, listener, Long.MaxValue) shouldBe 256
    ConnectionBudget.fit(Int.MaxValue, listener, Long.MaxValue) shouldBe Int.MaxValue

  it should "take more connections on a bigger heap, and fewer when each holds more" in:
    ConnectionBudget.fit(Int.MaxValue, rest, 2 * oneGiB) should be >
      ConnectionBudget.fit(Int.MaxValue, rest, oneGiB)
    ConnectionBudget.fit(Int.MaxValue, rest, oneGiB) should be <
      ConnectionBudget.fit(Int.MaxValue, quack, oneGiB)
    ConnectionBudget.fit(Int.MaxValue, rest, oneGiB) should be >
      ConnectionBudget.fit(Int.MaxValue, listener, oneGiB)

  // The FlightSQL edge's connections on probation, in their own tenth of the heap.

  private val flight    = ConnectionProbation.heldBytes(65536, tls = false)
  private val flightTls = ConnectionProbation.heldBytes(65536, tls = true)

  "the FlightSQL edge" should "read 80 KiB of a connection at the default header limit" in:
    ConnectionProbation.readBudget(65536) shouldBe 81920L
    ConnectionProbation.readBudget(1024) shouldBe 17408L

  it should "hold what a connection on probation can leave at the default header limit" in:
    flight shouldBe 4834640L
    flightTls shouldBe 2791760L
    ConnectionProbation.heldBytes(1024, tls = false) shouldBe 2297168L
    ConnectionProbation.heldBytes(1024, tls = true) shouldBe 1221968L

  it should "take a tenth of the heap, leaving the other listeners their fifths" in:
    ConnectionBudget.fit(1024, flight, oneGiB, ConnectionBudget.FlightHeapParts) shouldBe 22
    ConnectionBudget.fit(1024, flightTls, oneGiB, ConnectionBudget.FlightHeapParts) shouldBe 38
    ConnectionBudget.fit(1024, flight, chartHeap, ConnectionBudget.FlightHeapParts) shouldBe 44
    ConnectionBudget.fit(1024, flightTls, chartHeap, ConnectionBudget.FlightHeapParts) shouldBe 76
    ConnectionBudget.fit(16, flightTls, oneGiB, ConnectionBudget.FlightHeapParts) shouldBe 16
    // Leaving out the parts is a fifth, as the three HTTP listeners take.
    ConnectionBudget.fit(1024, flightTls, oneGiB) shouldBe 76

  it should "say a tenth at boot" in:
    ConnectionBudget.notice(
      "The FlightSQL edge",
      "PROXY_MAX_UNAUTHENTICATED_CONNECTIONS",
      2048,
      38,
      flightTls,
      oneGiB,
      heapParts = ConnectionBudget.FlightHeapParts
    ) shouldBe
      "The FlightSQL edge takes at most 38 of the 2048 connections of " +
      "PROXY_MAX_UNAUTHENTICATED_CONNECTIONS: each can hold 2726 KiB before any credential, against " +
      "a tenth of this 1024 MiB heap. Raise the heap (-Xmx) or lower the limits to take more"

  "maxBacklog" should "price a call at its headers in their worst shape" in:
    ConnectionProbation.callBytes(65536) shouldBe 112298L
    ConnectionProbation.callBytes(1024) shouldBe 4778L

  it should "be as many as the pool has threads at the chart's heap" in:
    ConnectionProbation.maxBacklog(512, 65536, chartHeap) shouldBe 512

  it should "be what fits a twentieth of a smaller heap" in:
    ConnectionProbation.maxBacklog(512, 65536, oneGiB) shouldBe 478
    ConnectionProbation.maxBacklog(512, 65536, 512L << 20) shouldBe 239
    ConnectionProbation.maxBacklog(512, 65536, 256L << 20) shouldBe 119

  it should "be at least one, on a heap too small for one" in:
    ConnectionProbation.maxBacklog(512, 65536, noMemoryFor) shouldBe 1

  it should "follow the pool and the header limit" in:
    ConnectionProbation.maxBacklog(16, 65536, oneGiB) shouldBe 16
    ConnectionProbation.maxBacklog(2048, 65536, chartHeap) shouldBe 956
    ConnectionProbation.maxBacklog(512, 1024, 256L << 20) shouldBe 512

  it should "leave what a flood can queue at about a tenth of the chart's heap" in:
    // Its own arithmetic in the scaladoc: the limit, and 17 calls for each place taken before it.
    val places = ConnectionBudget.fit(1024, flightTls, chartHeap, ConnectionBudget.FlightHeapParts)
    val calls  = ConnectionProbation.maxBacklog(512, 65536, chartHeap) + 17L * places
    calls shouldBe 1804L
    val queued = calls * ConnectionProbation.callBytes(65536)
    queued / (1L << 20) shouldBe 193L
    queued should be < chartHeap / 10

  "slots" should "say nothing when told not to, and take the same" in:
    val printed = new java.io.ByteArrayOutputStream()
    val taken   = Console.withOut(printed)(
      ConnectionBudget.slots("A listener", "AN_ENV", Int.MaxValue, 1L << 20, announce = false)
    )
    printed.toString("UTF-8") shouldBe empty
    taken shouldBe ConnectionBudget.fit(Int.MaxValue, 1L << 20, Runtime.getRuntime.maxMemory)

  it should "say so otherwise" in:
    val printed = new java.io.ByteArrayOutputStream()
    Console.withOut(printed)(ConnectionBudget.slots("A listener", "AN_ENV", Int.MaxValue, 1L << 20))
    printed.toString("UTF-8") should include("A listener takes at most")

  // What boot says when a listener takes fewer connections than it is configured to.

  "notice" should "say what the listener takes, why, and how to take more" in:
    ConnectionBudget.notice(
      "The REST port",
      "QOD_REST_MAX_CONNECTIONS",
      1024,
      ConnectionBudget.fit(1024, rest, oneGiB),
      rest,
      oneGiB
    ) shouldBe
      "The REST port takes at most 260 of the 1024 connections of QOD_REST_MAX_CONNECTIONS: each " +
      "can hold 806 KiB before any credential, against a fifth of this 1024 MiB heap. Raise the " +
      "heap (-Xmx) or lower the limits to take more"

  it should "stay true when not even one connection fits a fifth of the heap" in:
    // An operator's 2 GiB public body limit: one connection can hold twice the heap's fifth, and
    // the listener still takes one.
    val huge  = ConnectionBudget.heldBytes(8192, Int.MaxValue - 8L, tls = false)
    val taken = ConnectionBudget.fit(1024, huge, oneGiB)
    taken shouldBe 1
    huge should be > oneGiB / 5
    val line =
      ConnectionBudget.notice(
        "The REST port",
        "QOD_REST_MAX_CONNECTIONS",
        1024,
        taken,
        huge,
        oneGiB
      )
    line should include("takes at most 1 of the 1024 connections of QOD_REST_MAX_CONNECTIONS")
    // Said of one connection as of many: "takes 1 connections" read badly.
    line should not include "takes 1 connections"
    line should include(s"each can hold ${huge / 1024} KiB before any credential")
    line should include("against a fifth of this 1024 MiB heap")
    line should include("Raise the heap (-Xmx) or lower the limits to take more")
    // It does not claim that a fifth of the heap holds what it does not.
    line should not include "holds no more"

  it should "say only to raise the heap when the listener's limits are constants" in:
    // The node verdict listener's header and body limits are fixed: lowering them is no way to take
    // more, and no setting does it.
    val line = ConnectionBudget.notice(
      "The node verdict listener",
      "QOD_INTERNAL_MAX_CONNECTIONS",
      256,
      ConnectionBudget.fit(256, listener, oneGiB),
      listener,
      oneGiB,
      limitsTunable = false
    )
    line shouldBe
      "The node verdict listener takes at most 145 of the 256 connections of " +
      "QOD_INTERNAL_MAX_CONNECTIONS: each can hold 1446 KiB before any credential, against a " +
      "fifth of this 1024 MiB heap. Raise the heap (-Xmx) to take more"
    line should not include "lower the limits"
