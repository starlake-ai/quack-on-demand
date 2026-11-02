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
  private val chartHeap   = fourGiB * 70 / 100 // -XX:MaxRAMPercentage=70 under a 4 GiB limit
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
    ConnectionBudget.fit(1024, rest, chartHeap) shouldBe 728
    ConnectionBudget.fit(1024, quack, chartHeap) shouldBe 1024
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
