package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.{QuackResponse, TestArrow}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.ondemand.InProcessDuckDb
import org.apache.arrow.flight.{FlightCallHeaders, FlightClient, FlightInfo, HeaderCallOption}
import org.apache.arrow.flight.sql.FlightSqlClient
import org.duckdb.DuckDBConnection
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.util.Base64
import scala.sys.process.{Process, ProcessLogger}

/** A metadata call's stream carries the schema its FlightInfo announced. Its rows come from SQL,
  * whose columns are all nullable, and Arrow's standard schemas mark some not null, so the two used
  * to differ, and ADBC's `adbc_get_objects` failed with "inconsistent schema". Over a real DuckDB
  * with a catalog of its own, as the metadata calls leave out `memory`. The ADBC case is cancelled
  * without `uv`, which provisions the driver, as in [[SchemaProbeAdbcSpec]].
  */
class FlightMetadataSchemaSpec extends AnyFlatSpec with Matchers:

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

  /** An edge whose node is one DuckDB with a catalog `lake` holding `lake.sales.orders`. */
  private def withEdge[A](run: FlightEdgeHarness.Harness => A): A =
    val base = DriverManager
      .getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
      .asInstanceOf[DuckDBConnection]
    try
      val setup = base.createStatement()
      setup.execute("ATTACH ':memory:' AS lake")
      setup.execute("CREATE SCHEMA lake.sales")
      setup.execute("CREATE TABLE lake.sales.orders AS SELECT 1 AS id, 'a' AS name")
      val node: String => QuackResponse = sql =>
        val conn = base.duplicate()
        try
          val rs = conn.createStatement().executeQuery(sql)
          QuackResponse.Ok(TestArrow.fromResultSet(conn, rs), 1L, () => ())
        catch
          case e: Throwable =>
            conn.close()
            throw e
      val h = FlightEdgeHarness.boot(
        SecurityFixtures.freshStore().store,
        wiring = FlightEdgeHarness.RouterWiring(
          nodeQuery = node,
          spawnNodes = true,
          metadataFilterRewriter = new MetadataFilterRewriter(enabled = false),
          protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
        )
      )
      try run(h)
      finally h.shutdown()
    finally base.close()

  private def credentials: HeaderCallOption =
    val secret = s"${SecurityFixtures.AliceUsername}:${SecurityFixtures.AlicePassword}"
    val hdrs   = new FlightCallHeaders()
    hdrs.insert("tenant", SecurityFixtures.TenantName)
    hdrs.insert("pool", SecurityFixtures.PoolName)
    hdrs.insert(
      "authorization",
      s"Basic ${Base64.getEncoder.encodeToString(secret.getBytes("UTF-8"))}"
    )
    new HeaderCallOption(hdrs)

  /** The rows of a call's stream, having checked its schema against its FlightInfo's. */
  private def rowsUnderAnnounced(client: FlightClient, info: FlightInfo): Long =
    val stream = client.getStream(info.getEndpoints.get(0).getTicket, credentials)
    try
      stream.getSchema shouldBe info.getSchema
      var rows = 0L
      while stream.next() do rows += stream.getRoot.getRowCount
      rows
    finally stream.close()

  "every metadata call" should "stream the schema its FlightInfo announced" in withEdge { h =>
    val client = h.newClient()
    val sql    = new FlightSqlClient(client)
    try
      def rows(info: FlightInfo) = rowsUnderAnnounced(client, info)
      val orders = org.apache.arrow.flight.sql.util.TableRef.of("lake", "sales", "orders")
      rows(sql.getCatalogs(credentials)) shouldBe 1L
      rows(sql.getSchemas("lake", "sales", credentials)) shouldBe 1L
      rows(sql.getTables("lake", "sales", "orders", null, false, credentials)) shouldBe 1L
      rows(sql.getTables("lake", "sales", "orders", null, true, credentials)) shouldBe 1L
      rows(sql.getTableTypes(credentials)) shouldBe 2L
      rows(sql.getTables("lake", "no_such_schema", null, null, false, credentials)) shouldBe 0L
      rows(sql.getPrimaryKeys(orders, credentials)) shouldBe 0L
      rows(sql.getImportedKeys(orders, credentials)) shouldBe 0L
      rows(sql.getExportedKeys(orders, credentials)) shouldBe 0L
      rows(sql.getXdbcTypeInfo(credentials)) should be > 0L
      rows(sql.getSqlInfo(Array.empty[Int], credentials)) should be > 0L
    finally client.close()
  }

  "ADBC's adbc_get_objects" should "list the catalog, the schema, the table and its columns" in {
    assume(uvPresent, "uv not on PATH")
    withEdge { h =>
      val (rc, out, err) = python(
        s"""import adbc_driver_flightsql.dbapi as flight_sql
           |from adbc_driver_flightsql import DatabaseOptions
           |hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
           |conn = flight_sql.connect(
           |    uri="grpc://${h.host}:${h.port}",
           |    db_kwargs={
           |        "username": "${SecurityFixtures.AliceUsername}",
           |        "password": "${SecurityFixtures.AlicePassword}",
           |        hdr + "tenant": "${SecurityFixtures.TenantName}",
           |        hdr + "pool": "${SecurityFixtures.PoolName}",
           |    },
           |    autocommit=True,
           |)
           |for depth in ["catalogs", "db_schemas", "tables", "all"]:
           |    found = conn.adbc_get_objects(depth=depth, catalog_filter="lake", db_schema_filter="sales").read_all().to_pylist()
           |    print(depth, found[0]["catalog_name"], len(found))
           |    for schema in found[0]["catalog_db_schemas"] or []:
           |        for table in schema["db_schema_tables"] or []:
           |            names = [c["column_name"] for c in table["table_columns"] or []]
           |            print(schema["db_schema_name"], table["table_name"], names)
           |print(conn.adbc_get_table_schema("orders", catalog_filter="lake", db_schema_filter="sales").names)
           |conn.close()
           |""".stripMargin
      )
      withClue(err)(rc shouldBe 0)
      out.split("\n").toList shouldBe List(
        "catalogs lake 1",
        "db_schemas lake 1",
        "tables lake 1",
        "sales orders []",
        "all lake 1",
        "sales orders ['id', 'name']",
        "['id', 'name']"
      )
    }
  }
