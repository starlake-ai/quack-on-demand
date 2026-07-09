package ai.starlake.quack.it

import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackHttpAdapter, QuackHttpClient}
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.ArrowRowsDecoder
import ai.starlake.quack.ondemand.runtime.LocalQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.state.testkit.{PostgresFixture, TestPostgres}
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.net.{HttpURLConnection, URI}
import java.nio.file.Files
import scala.sys.process._

/** Spec 00 Task 8: live end-to-end proof that a catalog preview's rows equal a direct duckdb-CLI
  * `AT (VERSION => n)` query against the same DuckLake catalog.
  *
  * Runs the EXACT production pipeline: [[CatalogPreviewHandlers.buildSql]]'s output (reproduced
  * here verbatim -- `SELECT * FROM "schema"."table" AT (VERSION => n) LIMIT k`) is executed through
  * a real [[FlightSqlRouter.execute]] (`recordExecution = false`, mirroring the real executor
  * adapter in `Main.scala`) against a REAL spawned quack node ([[LocalQuackBackend]], the same
  * backend `PoolSupervisor.createPool` uses in production, never invoked as a raw script directly),
  * then decoded through the real [[ArrowRowsDecoder]]. The decoded rows are compared, after
  * deterministic ordering on both sides, against a direct `duckdb` CLI query of the same catalog at
  * the same snapshot.
  *
  * Cancels cleanly (not fails) when local Postgres or the `duckdb` CLI is unavailable -- same
  * environment guard [[StampedWriteIntegrationSpec]] and [[PostgresFixture]] use.
  */
class PreviewEndToEndSpec
    extends AnyFunSpec
    with Matchers
    with BeforeAndAfterAll
    with PostgresFixture:

  private val duckdbPresent: Boolean = Process("which duckdb").!(ProcessLogger(_ => ())) == 0

  private val allocator = new RootAllocator()

  override def afterAll(): Unit =
    allocator.close()

  /** Reproduces [[ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.buildSql]] verbatim (that
    * method is `private`, so the exact string shape is mirrored here rather than imported) --
    * `SELECT * FROM "schema"."table"[ AT (VERSION => n)] LIMIT k`. `sqlLimit` is the raw value
    * placed in the `LIMIT` clause; the handler's `preview` calls this with `effectiveLimit + 1`
    * (one row beyond the row cap actually returned to the caller) so
    * [[ai.starlake.quack.ondemand.api.ArrowRowsDecoder.decode]]'s truncation check has a row to
    * observe beyond its own `maxRows` cap -- see `previewRows` below, which mirrors that split.
    */
  private def buildPreviewSql(
      schema: String,
      table: String,
      snapshotId: Option[Long],
      sqlLimit: Int
  ): String =
    def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
    val target                        = s"${quoteIdent(schema)}.${quoteIdent(table)}"
    val atClause                      = snapshotId.fold("")(id => s" AT (VERSION => $id)")
    s"SELECT * FROM $target$atClause LIMIT $sqlLimit"

  /** Spawns a real quack node against `dbName`/`dataPath` (the fixture's live Postgres-backed
    * DuckLake catalog) via [[PoolSupervisor.createPool]] (never `spawn-quack-node.sh` directly),
    * runs `test`, then unwinds the pool/node/tenant-db/tenant it created. Cancels cleanly when the
    * spawned node never comes up (e.g. `duckdb` present but the `quack`/`ducklake` extensions
    * aren't installable in this environment).
    */
  private def withRouter[A](dbName: String, dataPath: java.nio.file.Path)(
      test: (FlightSqlRouter, PoolKey) => A
  ): A =
    val store    = new InMemoryControlPlaneStore()
    val tracker  = new NodeLoadTracker
    val backend  = new LocalQuackBackend(min = 24900, max = 24999)
    val sup      = new PoolSupervisor(backend, tracker, store)
    val tenant   = "pvtenant"
    val tenantDb = "pv_e2e"
    val poolName = "pv"
    val poolKey  = PoolKey(tenant, tenantDb, poolName)
    try
      sup.createTenant(Tenant(tenant)).unsafeRunSync() shouldBe a[Right[?, ?]]
      // Seed the tenant-db row directly (bypassing PoolSupervisor.createTenantDb, which would
      // provision a brand-new Postgres database + pre-init the DuckLake metadata tables -- the
      // fixture's `withCatalog` already did both), matching PreviewAuthzSpec's precedent.
      store.upsertTenantDb(
        TenantDb(
          id = "td-pv-e2e",
          tenantId = tenant,
          name = tenantDb,
          kind = TenantDbKind.DuckLake,
          metastore = Map(
            "pgHost"     -> TestPostgres.pgHost,
            "pgPort"     -> TestPostgres.pgPort.toString,
            "pgUser"     -> TestPostgres.pgUser,
            "pgPassword" -> TestPostgres.pgPass,
            "dbName"     -> dbName,
            "schemaName" -> "main"
          ),
          dataPath = dataPath.toString
        )
      )
      sup.restore()
      val running =
        try Some(sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync())
        catch case e: Throwable => None
      running match
        case None | Some(Nil) =>
          org.scalatest.Assertions.cancel(
            "quack node failed to spawn (duckdb/quack/ducklake extensions unavailable?)"
          )
        case Some(nodes) =>
          val node = nodes.head
          // Poll the spawned node's /quack endpoint the same way StampedWriteIntegrationSpec does,
          // so we don't race FlightSqlRouter.execute against a node still booting (INSTALL/LOAD +
          // ATTACH can take a couple seconds on a cold extension cache).
          val deadline  = System.currentTimeMillis() + 40000
          var up        = false
          var lastError = ""
          while !up && System.currentTimeMillis() < deadline do
            up =
              try
                val c = URI
                  .create(s"http://${node.host}:${node.port}/quack")
                  .toURL
                  .openConnection()
                  .asInstanceOf[HttpURLConnection]
                c.setConnectTimeout(300); c.setReadTimeout(300)
                c.getResponseCode; c.disconnect(); true
              catch
                case e: Throwable =>
                  lastError = e.toString
                  Thread.sleep(300); false
          if !up then
            org.scalatest.Assertions.cancel(
              s"quack node at ${node.host}:${node.port} never came up (last poll error: $lastError)"
            )
          val client   = new QuackHttpClient(allocator, nativeClient = true, nodeDisableSsl = true)
          val adapter  = new QuackHttpAdapter(client, tracker)
          val sessions = new ai.starlake.quack.edge.SessionRegistry
          val router   = new FlightSqlRouter(sup, sessions, tracker, adapter)
          test(router, poolKey)
    finally
      try sup.list().foreach(ps => sup.deletePool(ps.key, force = true).unsafeRunSync())
      catch case _: Throwable => ()

  private def previewRows(
      router: FlightSqlRouter,
      poolKey: PoolKey,
      schema: String,
      table: String,
      snapshotId: Option[Long],
      limit: Int
  ): (List[List[io.circe.Json]], Boolean) =
    // Mirrors CatalogPreviewHandlers.preview exactly: the SQL LIMIT is one row past the cap
    // (`limit + 1`) so the decoder can observe truncation; ArrowRowsDecoder.decode still stops
    // collecting at `limit`, so the response never carries the extra row.
    val sql = buildPreviewSql(schema, table, snapshotId, limit + 1)
    router
      .execute(
        connectionId = s"preview-${poolKey.tenant}-${poolKey.tenantDb}",
        user = "superuser",
        poolKey = poolKey,
        sql = sql,
        recordExecution = false
      )
      .unsafeRunSync() match
      case Left(failure) => fail(s"preview query failed: $failure")
      case Right(result) =>
        try
          val (_, rows, truncated) = ArrowRowsDecoder.decode(result.rows, limit)
          (rows, truncated)
        finally result.close()

  /** Direct duckdb-CLI query of the same catalog at the same snapshot, independent of the
    * FlightSQL/quack-node path entirely -- the ground truth `previewRows` is checked against.
    */
  private def directCliRows(
      dbName: String,
      dataPath: java.nio.file.Path,
      schema: String,
      table: String,
      snapshotId: Option[Long],
      pk: String
  ): List[String] =
    val atClause = snapshotId.fold("")(id => s" AT (VERSION => $id)")
    val sql      =
      s"""INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres;
         |ATTACH 'ducklake:postgres:host=${TestPostgres.pgHost} port=${TestPostgres.pgPort} dbname=$dbName user=${TestPostgres.pgUser} password=${TestPostgres.pgPass}' AS lake
         |  (DATA_PATH '${dataPath.toString}');
         |SELECT * FROM lake.$schema.$table$atClause ORDER BY $pk;
         |""".stripMargin
    val tmp = Files.createTempFile("preview-e2e-direct", ".sql")
    Files.writeString(tmp, sql)
    try
      val out = new StringBuilder
      val rc  =
        (s"duckdb -csv" #< tmp.toFile).!(ProcessLogger(line => out.append(line).append('\n')))
      assert(rc == 0, s"duckdb direct query exit=$rc: $out")
      // Drop the CSV header line; keep row lines verbatim for a row-shape-independent compare.
      out.toString.linesIterator.toList.drop(1).filter(_.nonEmpty)
    finally Files.deleteIfExists(tmp)

  /** Renders a decoded preview row (`List[Json]`) the same way duckdb's `-csv` output would join
    * cells, so both sides can be compared as plain strings without depending on JSON vs. native
    * DuckDB type representations lining up value-for-value.
    */
  private def renderRowAsCsv(row: List[io.circe.Json]): String =
    row
      .map { j =>
        if j.isNull then ""
        else
          j.asString.getOrElse(
            j.asNumber.map(_.toString).getOrElse(j.asBoolean.map(_.toString).getOrElse(j.toString))
          )
      }
      .mkString(",")

  describe("preview end-to-end against a real quack node") {

    it("returns rows identical to a direct duckdb AT (VERSION => n) query") {
      assume(duckdbPresent, "duckdb CLI not on PATH")
      TestPostgres.ensureReachable()
      withCatalog("pve2e") { (reader, dataPath) =>
        val dbName     = currentDbName.get
        val snapshotId = reader.maxSnapshotId()
        snapshotId.isDefined shouldBe true

        withRouter(dbName, dataPath) { (router, poolKey) =>
          val (rows, truncated) =
            previewRows(router, poolKey, "tpch1", "region", snapshotId, limit = 1000)
          truncated shouldBe false
          rows.size shouldBe 5

          val previewCsv = rows.map(renderRowAsCsv).sorted
          val directCsv  =
            directCliRows(
              dbName,
              dataPath,
              "tpch1",
              "region",
              snapshotId,
              pk = "r_regionkey"
            ).sorted

          previewCsv shouldBe directCsv
        }
      }
    }

    it("truncates: maxRows=2 on a 5-row table yields 2 rows and truncated=true") {
      assume(duckdbPresent, "duckdb CLI not on PATH")
      TestPostgres.ensureReachable()
      withCatalog("pve2etrunc") { (reader, dataPath) =>
        val dbName     = currentDbName.get
        val snapshotId = reader.maxSnapshotId()

        withRouter(dbName, dataPath) { (router, poolKey) =>
          val (rows, truncated) =
            previewRows(router, poolKey, "tpch1", "region", snapshotId, limit = 2)
          rows.size shouldBe 2
          truncated shouldBe true
        }
      }
    }
  }
