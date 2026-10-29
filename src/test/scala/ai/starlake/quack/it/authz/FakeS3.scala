package ai.starlake.quack.it.authz

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A path-style S3 endpoint on 127.0.0.1 for the per-user credential cases (decision 23). It serves
  * the objects it is given (HEAD, GET, ranged GET), answers 403 AccessDenied to a key id a bucket
  * does not allow, and logs the access key id of every request, read from its SigV4 `Authorization`
  * header (never verified), so a spec can tell whose keys the node signed with. One request per
  * connection (`Connection: close`).
  */
final class FakeS3(allowed: Map[String, Set[String]], objects: Map[(String, String), Array[Byte]]):
  import FakeS3.*

  private val log     = new ConcurrentLinkedQueue[Logged]()
  private val socket  = new ServerSocket()
  private val running = new AtomicBoolean(false)

  def start(): Unit =
    socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress, 0))
    running.set(true)
    val acceptor = new Thread(() => accept(), "fake-s3")
    acceptor.setDaemon(true)
    acceptor.start()

  def stop(): Unit =
    running.set(false)
    Try(socket.close())

  def port: Int = socket.getLocalPort

  /** The endpoint as a credential names it: plain HTTP. */
  def endpoint: String = s"http://127.0.0.1:$port"

  /** Every request so far, oldest first. */
  def requests: List[Logged] = log.asScala.toList

  private def accept(): Unit =
    while running.get() do
      Try(socket.accept()).foreach { client =>
        val worker = new Thread(() => serve(client), "fake-s3-client")
        worker.setDaemon(true)
        worker.start()
      }

  private def serve(client: Socket): Unit =
    try
      client.setSoTimeout(10000)
      val in    = client.getInputStream
      val head  = readHead(in)
      val lines = head.split("\r\n").toList
      val first = lines.headOption.getOrElse("").split(" ")
      if first.length >= 2 then
        val method  = first(0)
        val path    = first(1).takeWhile(_ != '?')
        val headers = lines
          .drop(1)
          .flatMap { line =>
            val colon = line.indexOf(':')
            Option.when(colon > 0)(
              line.substring(0, colon).trim.toLowerCase(java.util.Locale.ROOT) ->
                line.substring(colon + 1).trim
            )
          }
          .toMap
        // A body (a PUT the fake does not serve) is read and dropped.
        headers.get("content-length").flatMap(_.toIntOption).foreach(n => in.readNBytes(n))
        val answer   = respond(method, path, headers)
        val response = new StringBuilder(s"HTTP/1.1 ${answer.status} ${reason(answer.status)}\r\n")
        (answer.headers ++ List(
          "Content-Length" -> answer.body.length.toString,
          "Connection"     -> "close"
        )).foreach((name, value) => response.append(s"$name: $value\r\n"))
        response.append("\r\n")
        val out = client.getOutputStream
        out.write(response.toString.getBytes(StandardCharsets.US_ASCII))
        if method != "HEAD" then out.write(answer.body)
        out.flush()
    catch case _: java.io.IOException => ()
    finally Try(client.close())

  private def respond(method: String, path: String, headers: Map[String, String]): Answer =
    val keyId = headers
      .get("authorization")
      .flatMap(a => CredentialOf.findFirstMatchIn(a).map(_.group(1)))
      .getOrElse("")
    log.add(Logged(keyId, method, path))
    val parts  = path.stripPrefix("/").split("/", 2)
    val bucket = parts(0)
    val key    = if parts.length > 1 then parts(1) else ""
    if !allowed.getOrElse(bucket, Set.empty).contains(keyId) then
      failure(403, "AccessDenied", "Access Denied")
    else if method != "GET" && method != "HEAD" then
      failure(405, "MethodNotAllowed", "The fake serves HEAD and GET only.")
    else
      objects.get((bucket, key)) match
        case None        => failure(404, "NoSuchKey", "The specified key does not exist.")
        case Some(bytes) =>
          val common = List(
            "ETag"          -> s"\"${Integer.toHexString(java.util.Arrays.hashCode(bytes))}\"",
            "Last-Modified" -> LastModified,
            "Accept-Ranges" -> "bytes",
            "Content-Type"  -> "application/octet-stream"
          )
          headers.get("range").flatMap(RangeOf.findFirstMatchIn) match
            case Some(m) if method == "GET" =>
              val start = m.group(1).toInt
              val end   = Option(m.group(2))
                .filter(_.nonEmpty)
                .fold(bytes.length - 1)(e => math.min(e.toInt, bytes.length - 1))
              Answer(
                206,
                common :+ ("Content-Range" -> s"bytes $start-$end/${bytes.length}"),
                bytes.slice(start, end + 1)
              )
            case _ => Answer(200, common, bytes)

object FakeS3:

  /** One request: the key id it was signed with ("" when unsigned), its method and path. */
  final case class Logged(keyId: String, method: String, path: String)

  private final case class Answer(status: Int, headers: List[(String, String)], body: Array[Byte])

  private val CredentialOf = "Credential=([^/,\\s]+)/".r
  private val RangeOf      = "bytes=(\\d+)-(\\d*)".r
  private val LastModified = "Wed, 21 Oct 2026 07:28:00 GMT"

  private def failure(status: Int, code: String, message: String): Answer =
    val xml =
      s"""<?xml version="1.0" encoding="UTF-8"?><Error><Code>$code</Code><Message>$message</Message></Error>"""
    Answer(status, List("Content-Type" -> "application/xml"), xml.getBytes(StandardCharsets.UTF_8))

  private def reason(status: Int): String = status match
    case 200 => "OK"
    case 206 => "Partial Content"
    case 403 => "Forbidden"
    case 404 => "Not Found"
    case _   => "Method Not Allowed"

  /** The request head, up to the blank line that ends it (64 KiB at most). */
  private def readHead(in: InputStream): String =
    val bytes = new ByteArrayOutputStream()
    var last4 = 0
    var done  = false
    while !done do
      val b = in.read()
      if b < 0 || bytes.size() >= 65536 then done = true
      else
        bytes.write(b)
        last4 = (last4 << 8) | b
        done = last4 == 0x0d0a0d0a
    bytes.toString(StandardCharsets.US_ASCII)
