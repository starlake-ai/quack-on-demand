package ai.starlake.quack.edge.auth

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** A server that is not quite HTTP, for the cases that look at what the JDK's `HttpClient` does
  * with an answer it refuses. For every connection it reads the head of the request, writes the
  * answer as it is (raw bytes, whatever they are) and then holds the connection open, waiting for
  * the client to hang up as a server does that expects it to.
  *
  * `answerFor` gives the answer of the connection with that index (0 for the first one accepted),
  * so a case can serve one malformed answer and good ones after it. With `repeat` the connection is
  * kept for the next request after an answer, as a keep-alive server does: every request head on it
  * is answered the same way, until the client hangs up.
  *
  * The JDK keeps a connection whose response headers it refuses for as long as the client lives
  * (its descriptor and its buffers stay reachable from the client), so the connection of such an
  * answer is the one to watch: [[awaitClosed]] says whether the client let go of it. [[heads]]
  * holds the request heads it read, for the cases that look at what the client sent. Close the
  * server when the case is done.
  */
final class MalformedServer(answerFor: Int => Array[Byte], repeat: Boolean = false)
    extends AutoCloseable:

  def this(answer: Array[Byte]) = this(_ => answer, false)

  private val socket   = new ServerSocket(0, 256, InetAddress.getLoopbackAddress)
  private val accepted = new AtomicInteger
  private val hungUp   = new Semaphore(0)
  private val open     = ConcurrentHashMap.newKeySet[Socket]()
  private val seen     = new CopyOnWriteArrayList[String]()

  private val acceptor = new Thread(() => acceptLoop(), "malformed-server")
  acceptor.setDaemon(true)
  acceptor.start()

  def base: String = s"http://127.0.0.1:${socket.getLocalPort}"

  /** How many connections were accepted. */
  def connections: Int = accepted.get()

  /** The request heads read so far (request line and headers), in arrival order. */
  def heads: List[String] = seen.asScala.toList

  /** True once the client has closed `connections` connections (one by default), within
    * `timeoutMs`. A connection counts when the client's end is gone: a close, or a reset.
    */
  def awaitClosed(timeoutMs: Long, connections: Int = 1): Boolean =
    hungUp.tryAcquire(connections, timeoutMs, TimeUnit.MILLISECONDS)

  private def acceptLoop(): Unit =
    try
      while !socket.isClosed do
        val connection = socket.accept()
        val index      = accepted.getAndIncrement()
        open.add(connection)
        val worker = new Thread(() => serve(connection, index), "malformed-server-connection")
        worker.setDaemon(true)
        worker.start()
    catch case _: IOException => ()

  private def serve(connection: Socket, index: Int): Unit =
    try
      val in  = connection.getInputStream
      val out = connection.getOutputStream
      var go  = true
      while go do
        val head = readHead(in)
        if head.isEmpty then go = false
        else
          seen.add(head)
          out.write(answerFor(index))
          out.flush()
          go = repeat
      // The client reads the answer and, if it is well made, hangs up: wait for that.
      while in.read() >= 0 do ()
    catch case _: IOException => ()
    finally
      hungUp.release()
      open.remove(connection)
      try connection.close()
      catch case _: IOException => ()

  /** Reads the request line and headers, up to and including the blank line; "" at the end of the
    * stream.
    */
  private def readHead(in: InputStream): String =
    val CrLfCrLf = 0x0d0a0d0a
    val head     = new ByteArrayOutputStream()
    var window   = 0 // the last four bytes read
    var done     = false
    while !done do
      val b = in.read()
      if b < 0 then done = true
      else
        head.write(b)
        window = (window << 8) | b
        done = window == CrLfCrLf
    if window == CrLfCrLf then new String(head.toByteArray, StandardCharsets.ISO_8859_1) else ""

  override def close(): Unit =
    try socket.close()
    catch case _: IOException => ()
    open.forEach { connection =>
      try connection.close()
      catch case _: IOException => ()
    }
