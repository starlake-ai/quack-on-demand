package ai.starlake.quack.security

import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.sys.process.{Process, ProcessLogger}

/** What the C++ and Go clients read when a call is refused while they are still writing it. A
  * status alone ends such a call: a reset after it could replace it for a C++ client, so none
  * follows. Cancelled without `uv`, which provisions the clients, as in [[SchemaProbeAdbcSpec]].
  */
class FlightRefusedRealClientSpec extends AnyFlatSpec with Matchers:

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

  private def withEdge[A](run: FlightEdgeHarness.Harness => A): A =
    val h = FlightEdgeHarness.boot(
      SecurityFixtures.freshStore().store,
      wiring = FlightEdgeHarness.RouterWiring(
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      )
    )
    try run(h)
    finally h.shutdown()

  "pyarrow" should "read UNAUTHENTICATED each time from a DoPut whose bearer fails, its writer open" in:
    assume(uvPresent, "uv not on PATH")
    withEdge { h =>
      val (rc, out, err) = python(
        s"""import pyarrow as pa
           |import pyarrow.flight as flight
           |client = flight.connect("grpc://${h.host}:${h.port}")
           |options = flight.FlightCallOptions(timeout=30, headers=[
           |    (b"authorization", b"Bearer nope"),
           |    (b"tenant", b"${SecurityFixtures.TenantName}"),
           |    (b"pool", b"${SecurityFixtures.PoolName}"),
           |])
           |descriptor = flight.FlightDescriptor.for_command(b"x")
           |outcomes = []
           |for _ in range(16):
           |    try:
           |        writer, reader = client.do_put(descriptor, pa.schema([]), options=options)
           |        # The writer stays open until the server has answered: only closing it reads the status.
           |        reader.read()
           |        writer.close()
           |        outcomes.append("answered")
           |    except Exception as e:
           |        outcomes.append(type(e).__name__)
           |print(len(outcomes), sorted(set(outcomes)))
           |""".stripMargin
      )
      withClue(err)(rc shouldBe 0)
      out shouldBe "16 ['FlightUnauthenticatedError']"
    }

  it should "read a retryable error past 16 streams with no working credential, then go on" in:
    assume(uvPresent, "uv not on PATH")
    withEdge { h =>
      // Its message follows its HEADERS at once, and Netty takes that message on a refused stream for
      // a stream that never existed, which closes the connection: the client reconnects by itself.
      val (rc, out, err) = python(
        s"""import time
           |import pyarrow as pa
           |import pyarrow.flight as flight
           |client = flight.connect("grpc://${h.host}:${h.port}")
           |options = flight.FlightCallOptions(timeout=30, headers=[
           |    (b"authorization", b"Bearer nope"),
           |    (b"tenant", b"${SecurityFixtures.TenantName}"),
           |    (b"pool", b"${SecurityFixtures.PoolName}"),
           |])
           |descriptor = flight.FlightDescriptor.for_command(b"x")
           |def attempt():
           |    try:
           |        writer, reader = client.do_put(descriptor, pa.schema([]), options=options)
           |        reader.read()
           |        writer.close()
           |        return "answered"
           |    except Exception as e:
           |        return type(e).__name__
           |first = {attempt() for _ in range(16)}
           |past = attempt()
           |deadline = time.time() + 30
           |back = attempt()
           |while back != "FlightUnauthenticatedError" and time.time() < deadline:
           |    time.sleep(0.05)
           |    back = attempt()
           |print(sorted(first), past, back)
           |""".stripMargin
      )
      withClue(err)(rc shouldBe 0)
      out shouldBe "['FlightUnauthenticatedError'] FlightUnavailableError FlightUnauthenticatedError"
    }

  it should "have wait_for_available() return with no credentials" in:
    assume(uvPresent, "uv not on PATH")
    withEdge { h =>
      val (rc, out, err) = python(
        s"""import pyarrow.flight as flight
           |client = flight.connect("grpc://${h.host}:${h.port}")
           |client.wait_for_available(timeout=30)
           |print("available")
           |""".stripMargin
      )
      withClue(err)(rc shouldBe 0)
      out shouldBe "available"
    }

  "ADBC" should "read UNAUTHENTICATED each time from an update whose bearer fails" in:
    assume(uvPresent, "uv not on PATH")
    withEdge { h =>
      val (rc, out, err) = python(
        s"""import adbc_driver_flightsql.dbapi as flight_sql
           |from adbc_driver_flightsql import DatabaseOptions
           |hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
           |conn = flight_sql.connect(
           |    uri="grpc://${h.host}:${h.port}",
           |    db_kwargs={
           |        DatabaseOptions.AUTHORIZATION_HEADER.value: "Bearer nope",
           |        hdr + "tenant": "${SecurityFixtures.TenantName}",
           |        hdr + "pool": "${SecurityFixtures.PoolName}",
           |    },
           |    autocommit=True,
           |)
           |outcomes = []
           |for _ in range(12):
           |    cur = conn.cursor()
           |    try:
           |        cur.adbc_statement.set_sql_query("INSERT INTO t VALUES (1)")
           |        cur.adbc_statement.execute_update()
           |        outcomes.append("answered")
           |    except Exception as e:
           |        outcomes.append("UNAUTHENTICATED" if "UNAUTHENTICATED" in str(e) else str(e)[:200])
           |print(len(outcomes), sorted(set(outcomes)))
           |""".stripMargin
      )
      withClue(err)(rc shouldBe 0)
      out shouldBe "12 ['UNAUTHENTICATED']"
    }
