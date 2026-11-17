package ai.starlake.quack.edge.adapter

import java.io.{BufferedInputStream, IOException, InputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** A Quack node whose process hangs, on a real local socket: it accepts every connection and never
  * answers a request on it, so a JDK send to it waits for as long as its request lets it. With
  * `grantConnection` it answers each CONNECTION_REQUEST as a node would, closing that connection
  * after (`Connection: close`), and stays silent on every other request: a node that granted a
  * connection and hung after.
  *
  * [[open]] counts the connections the client still holds, each waiting on a request: one the
  * client closed, or that the node closed once it granted, no longer counts. [[types]] lists the
  * message type of each request it read, in arrival order (only with `grantConnection`, the one
  * mode that reads them). [[close]] closes the node's side of every connection, which ends any send
  * still waiting on it.
  */
final class SilentNode(grantConnection: Boolean = false) extends AutoCloseable:

  private val server  = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
  private val sockets = ConcurrentLinkedQueue[Socket]()
  private val ended   = AtomicInteger(0)
  private val typeLog = ConcurrentLinkedQueue[Int]()

  val port: Int               = server.getLocalPort
  val accepted: AtomicInteger = AtomicInteger(0)

  def open: Int        = accepted.get() - ended.get()
  def types: List[Int] = typeLog.asScala.toList

  daemon("silent-node-accept") {
    try
      while true do
        val s = server.accept()
        sockets.add(s)
        accepted.incrementAndGet()
        daemon("silent-node-connection")(serve(s))
    catch case _: IOException => () // closed
  }

  private def serve(s: Socket): Unit =
    try
      val in      = new BufferedInputStream(s.getInputStream)
      val granted = grantConnection && readRequest(in).exists { body =>
        val t = QuackNativeBridge.parseMessageType(body)
        typeLog.add(t)
        t == MessageType.ConnectionRequest.wireOrdinal
      }
      if granted then
        val reply = QuackTestFixtures.serializeSampleConnectionResponse("silent-connection")
        val out   = s.getOutputStream
        out.write(
          ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.duckdb\r\n" +
            s"Content-Length: ${reply.length}\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII)
        )
        out.write(reply)
        out.flush()
        s.close()
      else
        // Silent: whatever else comes is read and never answered, until the client closes.
        while in.read() >= 0 do ()
    catch case _: IOException => ()
    finally ended.incrementAndGet()

  /** One HTTP/1.1 request's body, or None when the client closed first. */
  private def readRequest(in: InputStream): Option[Array[Byte]] =
    val head = new StringBuilder
    while !head.endsWith("\r\n\r\n") do
      val c = in.read()
      if c < 0 then return None
      head.append(c.toChar)
    val length = head.toString.linesIterator
      .map(_.trim)
      .collectFirst {
        case l if l.toLowerCase.startsWith("content-length:") => l.drop(15).trim.toInt
      }
      .getOrElse(0)
    Some(in.readNBytes(length)).filter(_.length == length)

  private def daemon(name: String)(body: => Unit): Unit =
    val t = new Thread(() => body, name)
    t.setDaemon(true)
    t.start()

  def close(): Unit =
    server.close()
    sockets.asScala.foreach(s =>
      try s.close()
      catch case _: IOException => ()
    )
