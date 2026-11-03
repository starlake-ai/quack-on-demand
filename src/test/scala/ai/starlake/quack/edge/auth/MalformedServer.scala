package ai.starlake.quack.edge.auth

import java.io.{IOException, InputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.util.concurrent.{ConcurrentHashMap, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** A server that is not quite HTTP, for the cases that look at what the JDK's `HttpClient` does
  * with an answer it refuses. For every connection it reads the head of the request, writes
  * `answer` as it is (raw bytes, whatever they are) and then holds the connection open, waiting for
  * the client to hang up as a server does that expects it to.
  *
  * The JDK keeps a connection whose response headers it refuses for as long as the client lives
  * (its descriptor and its buffers stay reachable from the client), so the connection of such an
  * answer is the one to watch: [[awaitClosed]] says whether the client let go of it. Close the
  * server when the case is done.
  */
final class MalformedServer(answer: Array[Byte]) extends AutoCloseable:
  private val socket   = new ServerSocket(0, 256, InetAddress.getLoopbackAddress)
  private val accepted = new AtomicInteger
  private val hungUp   = new Semaphore(0)
  private val open     = ConcurrentHashMap.newKeySet[Socket]()

  private val acceptor = new Thread(() => acceptLoop(), "malformed-server")
  acceptor.setDaemon(true)
  acceptor.start()

  def base: String = s"http://127.0.0.1:${socket.getLocalPort}"

  /** How many connections were accepted. */
  def connections: Int = accepted.get()

  /** True once the client has closed `connections` connections (one by default), within
    * `timeoutMs`. A connection counts when the client's end is gone: a close, or a reset.
    */
  def awaitClosed(timeoutMs: Long, connections: Int = 1): Boolean =
    hungUp.tryAcquire(connections, timeoutMs, TimeUnit.MILLISECONDS)

  private def acceptLoop(): Unit =
    try
      while !socket.isClosed do
        val connection = socket.accept()
        accepted.incrementAndGet()
        open.add(connection)
        val worker = new Thread(() => serve(connection), "malformed-server-connection")
        worker.setDaemon(true)
        worker.start()
    catch case _: IOException => ()

  private def serve(connection: Socket): Unit =
    try
      val in = connection.getInputStream
      readHead(in)
      val out = connection.getOutputStream
      out.write(answer)
      out.flush()
      // The client reads the answer and, if it is well made, hangs up: wait for that.
      while in.read() >= 0 do ()
    catch case _: IOException => ()
    finally
      hungUp.release()
      open.remove(connection)
      try connection.close()
      catch case _: IOException => ()

  /** Reads the request line and headers, up to and including the blank line. */
  private def readHead(in: InputStream): Unit =
    val CrLfCrLf = 0x0d0a0d0a
    var window   = 0 // the last four bytes read
    var done     = false
    while !done do
      val b = in.read()
      if b < 0 then done = true
      else
        window = (window << 8) | b
        done = window == CrLfCrLf

  override def close(): Unit =
    try socket.close()
    catch case _: IOException => ()
    open.forEach { connection =>
      try connection.close()
      catch case _: IOException => ()
    }
