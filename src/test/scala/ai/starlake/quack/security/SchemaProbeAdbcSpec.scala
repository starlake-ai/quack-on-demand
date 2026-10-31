package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.{QuackResponse, TestArrow}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.ondemand.InProcessDuckDb
import org.duckdb.DuckDBConnection
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.jdk.CollectionConverters._
import scala.sys.process.{Process, ProcessLogger}

/** Audit F17 through the real ADBC FlightSQL driver: one `cursor.execute` prepares, asks
  * GetFlightInfo and reads DoGet. The schema probe used to run a batch led by a non-write statement
  * in full at Prepare, unrecorded, and DoGet ran it again: every row was written twice. The edge
  * now leaves such a batch unprobed and advertises no schema, which ADBC must read as "take the
  * stream's" (a zero-field schema would fail its schema-consistency check).
  *
  * The node executes against ONE DuckDB database shared by every call, so a second run shows as a
  * second row. Cancelled without `uv` (the driver is provisioned through it, as
  * [[ai.starlake.quack.it.QuackCompatibilitySpec]] provisions the DuckDB Python client).
  */
class SchemaProbeAdbcSpec extends AnyFlatSpec with Matchers:

  private val uvPresent: Boolean =
    scala.util.Try(Process("which uv").!(ProcessLogger(_ => ()))).toOption.contains(0)

  private def python(script: String): (Int, String, String) =
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = Process(
      Seq(
        "uv",
        "run",
        "--no-project",
        "--with",
        "adbc-driver-flightsql",
        "--with",
        "pyarrow",
        "python",
        "-c",
        script
      )
    ).!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString.trim, err.toString.trim)

  "a batch led by a non-write statement" should "write once through ADBC's prepare and execute" in:
    assume(uvPresent, "uv not on PATH")
    val base = DriverManager
      .getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
      .asInstanceOf[DuckDBConnection]
    val received = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    try
      base.createStatement().execute(OpaEdgeFixtures.OrdersSetup)
      val node: String => QuackResponse = sql =>
        received.add(sql)
        val conn = base.duplicate()
        try
          val stmt  = conn.createStatement()
          val parts = sql.split(";").map(_.trim).filter(_.nonEmpty).toList
          parts.init.foreach(stmt.execute)
          QuackResponse.Ok(
            TestArrow.fromResultSet(conn, stmt.executeQuery(parts.last)),
            1L,
            () => ()
          )
        catch
          case e: Throwable =>
            conn.close()
            throw e
      val fix = SecurityFixtures.freshStore()
      val h   = FlightEdgeHarness.boot(
        fix.store,
        wiring = FlightEdgeHarness.RouterWiring(
          nodeQuery = node,
          spawnNodes = true,
          metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
          protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
        )
      )
      try
        val (rc, out, err) = python(
          s"""import adbc_driver_flightsql.dbapi as flight_sql
             |from adbc_driver_flightsql import DatabaseOptions
             |hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
             |conn = flight_sql.connect(
             |    uri="grpc://${h.host}:${h.port}",
             |    db_kwargs={
             |        "username": "${SecurityFixtures.AliceUsername}",
             |        "password": "${SecurityFixtures.AlicePassword}",
             |        hdr + "tenant": "${SecurityFixtures.TenantId}",
             |        hdr + "pool": "${SecurityFixtures.PoolName}",
             |    },
             |    autocommit=True,
             |)
             |cur = conn.cursor()
             |cur.execute("SET VARIABLE v = 1; INSERT INTO orders VALUES (3, 'c@x.io'); SELECT count(*) AS n FROM orders")
             |print(cur.fetchall()[0][0])
             |cur.execute("SHOW TABLES")
             |print(sorted(r[0] for r in cur.fetchall()))
             |conn.close()
             |""".stripMargin
        )
        withClue(err)(rc shouldBe 0)
        out.split("\n").toList shouldBe List("3", "['orders']")
        received.asScala.count(_.contains("INSERT INTO orders")) shouldBe 1
        val rs = base.createStatement().executeQuery("SELECT count(*) FROM orders")
        rs.next()
        rs.getLong(1) shouldBe 3L
      finally h.shutdown()
    finally base.close()
