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
  private val chartHeap   = (4L << 30) * 70 / 100 // -XX:MaxRAMPercentage=70 under a 4 GiB limit
  private val fiveMiB     = 5L << 20
  private val noMemoryFor = 1L << 20

  "heldBytes" should "be what a connection holds at each listener's defaults" in:
    rest shouldBe 727040L
    quack shouldBe 399360L
    quackTls shouldBe 568320L
    listener shouldBe 1316864L

  it should "take the larger of the two stages a connection can be in" in:
    val perByte = ConnectionBudget.HeapPerParsedByte
    val base    = ConnectionBudget.ConnectionBytes
    // Waiting for the blank line: a parsed request line and a parsed header block, each at its limit.
    // REST at its defaults is here (the body stage is 425984).
    rest shouldBe base + 2L * 8192 * perByte
    // Reading a body: the block, the rest of the first read and the body. The listener's 1 MiB
    // body outweighs two parsed 4 KiB stages.
    listener shouldBe base + 4096L * perByte + BoundedBody.ReadBufferBytes + 1048576L

  it should "grow with each of its arguments, and with TLS" in:
    val held: (Int, Long, Boolean) => Long = ConnectionBudget.heldBytes
    held(2048, 65536L, false) should be < held(4096, 65536L, false)
    held(4096, 65536L, false) should be < held(8192, 65536L, false)
    held(4096, 65536L, false) should be < held(4096, 1048576L, false)
    held(4096, 1048576L, false) should be < held(4096, 8388608L, false)
    held(4096, 65536L, false) should be < held(4096, 65536L, true)
    ConnectionBudget.heldBytes(4096, 65536L, tls = true) - quack shouldBe ConnectionBudget.TlsBytes

  "fit" should "take what a fifth of the heap holds, at a heap of 1 GiB" in:
    ConnectionBudget.fit(1024, rest, oneGiB) shouldBe 295
    ConnectionBudget.fit(1024, quack, oneGiB) shouldBe 537
    ConnectionBudget.fit(1024, quackTls, oneGiB) shouldBe 377
    ConnectionBudget.fit(256, listener, oneGiB) shouldBe 163

  it should "leave each listener at most a fifth of the heap" in:
    for
      heap <- List(oneGiB, 2L << 30, 4L << 30, 16L << 30)
      held <- List(rest, quack, quackTls, listener)
    do
      val taken = ConnectionBudget.fit(Int.MaxValue, held, heap)
      withClue(s"heap $heap, held $held: ")(taken.toLong * held should be <= heap / 5)

  it should "take what is configured on the chart's heap" in:
    ConnectionBudget.fit(1024, rest, chartHeap) shouldBe 827
    ConnectionBudget.fit(1024, quack, chartHeap) shouldBe 1024
    ConnectionBudget.fit(256, listener, chartHeap) shouldBe 256

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
