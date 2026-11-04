package ai.starlake.quack.edge.auth

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** A server for the cases that are not quite HTTP: it reads a request in full, then answers with a
  * malformed status line (or a malformed `Content-Length`) that quotes the request's body, or the
  * value of its `Authorization` header. The JDK client refuses such an answer with an exception
  * whose message quotes the line, so a client that puts the text of a failure in its error would
  * carry its own request into it: a token request's body holds the client secret, and an OPA
  * request's header holds a bearer token. Close it when the case is done.
  */
final class EchoingServer(shape: EchoingServer.Shape) extends AutoCloseable:
  private val socket   = new ServerSocket(0, 16, InetAddress.getLoopbackAddress)
  private val answered = new AtomicInteger

  private val acceptor = new Thread(() => acceptLoop(), "echoing-server")
  acceptor.setDaemon(true)
  acceptor.start()

  def base: String = s"http://127.0.0.1:${socket.getLocalPort}"

  private def acceptLoop(): Unit =
    try
      while !socket.isClosed do
        val connection = socket.accept()
        val worker     = new Thread(() => serve(connection), "echoing-server-connection")
        worker.setDaemon(true)
        worker.start()
    catch case _: IOException => ()

  /** How many requests were read in full and answered. */
  def requests: Int = answered.get()

  private def serve(connection: Socket): Unit =
    try
      val in      = connection.getInputStream
      val headers = readHeaders(in)
      val length  = headers.linesIterator
        .collectFirst {
          case line if line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:") =>
            line.substring("content-length:".length).trim.toInt
        }
        .getOrElse(0)
      val body          = new String(in.readNBytes(length), StandardCharsets.ISO_8859_1)
      val authorization = headers.linesIterator
        .collectFirst {
          case line if line.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:") =>
            line.substring("authorization:".length).trim
        }
        .getOrElse("")
      val answer = shape match
        case EchoingServer.Shape.StatusLine    => s"NOT-HTTP $body\r\n\r\n"
        case EchoingServer.Shape.ContentLength =>
          s"HTTP/1.1 200 OK\r\nContent-Length: $body\r\n\r\n"
        case EchoingServer.Shape.AuthorizationHeader => s"NOT-HTTP $authorization\r\n\r\n"
      // Counted before the answer goes out, so that a client that has seen the answer finds it.
      answered.incrementAndGet()
      val out = connection.getOutputStream
      out.write(answer.getBytes(StandardCharsets.ISO_8859_1))
      out.flush()
      connection.shutdownOutput()
      // The client reads the answer and closes: give it the time, then let go.
      Thread.sleep(300)
    catch case _: Exception => ()
    finally
      try connection.close()
      catch case _: IOException => ()

  /** The request line and headers, up to and including the blank line. */
  private def readHeaders(in: InputStream): String =
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
    new String(head.toByteArray, StandardCharsets.ISO_8859_1)

  override def close(): Unit =
    try socket.close()
    catch case _: IOException => ()

object EchoingServer:

  /** Where the echo of the request goes. */
  enum Shape:
    /** `NOT-HTTP <body>`: the JDK refuses it as an invalid status line, quoting the line. */
    case StatusLine

    /** A valid status line, then `Content-Length: <body>`: refused as a number it cannot read,
      * quoting the value.
      */
    case ContentLength

    /** `NOT-HTTP <the Authorization header>`: refused as an invalid status line, quoting the
      * credential the request carried.
      */
    case AuthorizationHeader
