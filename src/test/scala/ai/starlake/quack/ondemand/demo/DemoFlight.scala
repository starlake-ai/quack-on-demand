package ai.starlake.quack.ondemand.demo

import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.flight.{FlightCallHeaders, FlightClient, HeaderCallOption, Location}
import org.apache.arrow.memory.RootAllocator

import java.util.Base64
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** A FlightSQL query against a demo edge: TLS with a self-signed certificate, tenant `acme`, pool
  * `bi`, as the demo's banner tells a client to connect.
  */
object DemoFlight:

  private def headers(user: String, password: String): HeaderCallOption =
    val hdrs  = new FlightCallHeaders()
    val basic = Base64.getEncoder.encodeToString(s"$user:$password".getBytes("UTF-8"))
    hdrs.insert("tenant", "acme")
    hdrs.insert("pool", "bi")
    hdrs.insert("authorization", s"Basic $basic")
    new HeaderCallOption(hdrs)

  def query(port: Int, user: String, password: String, sql: String): List[Map[String, String]] =
    val allocator = new RootAllocator()
    val raw       = FlightClient
      .builder(allocator, Location.forGrpcTls("127.0.0.1", port))
      .useTls()
      .verifyServer(false)
      .build()
    try
      val fsql   = new FlightSqlClient(raw)
      val opt    = headers(user, password)
      val info   = fsql.execute(sql, opt)
      val stream = fsql.getStream(info.getEndpoints.get(0).getTicket, opt)
      try
        val fields = stream.getSchema.getFields.asScala.map(_.getName).toList
        val rows   = ListBuffer.empty[Map[String, String]]
        while stream.next() do
          val root = stream.getRoot
          for i <- 0 until root.getRowCount do
            rows += fields
              .map(n => n -> Option(root.getVector(n).getObject(i)).map(_.toString).getOrElse(""))
              .toMap
        rows.toList
      finally stream.close()
    finally
      raw.close()
      allocator.close()

  /** Covers the window where the edge accepts connections and the node is still starting. */
  def queryRetrying(
      port: Int,
      user: String,
      password: String,
      sql: String,
      timeout: FiniteDuration
  ): List[Map[String, String]] =
    val deadline = System.nanoTime() + timeout.toNanos
    @tailrec
    def attempt(last: Option[Throwable]): List[Map[String, String]] =
      if System.nanoTime() >= deadline then
        throw new RuntimeException(s"never succeeded within $timeout: $sql", last.orNull)
      else
        Try(query(port, user, password, sql)) match
          case Success(rows) => rows
          case Failure(e)    =>
            Thread.sleep(2000)
            attempt(Some(e))
    attempt(None)
