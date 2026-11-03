package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.BranchingConfig
import ai.starlake.quack.ondemand.InProcessDuckDb
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Using}

/** The benchmark's table and writes, shared with the large case of `BranchThreeWayE2ESpec`. */
object ThreeWayMergeScaleBench:

  /** Every column of a new row, by name, as an expression over the row's `id`. */
  def created(id: String): List[(String, String)] = List(
    "id"          -> id,
    "customer_id" -> s"(hash($id) % 10000000)::BIGINT",
    "product_id"  -> s"(hash($id+1) % 100000)::INT",
    "status"      -> s"['NEW','PAID','SHIPPED','RETURNED','CANCELLED'][(hash($id+2) % 5)::INT + 1]",
    "amount"      -> s"((hash($id+3) % 10000000)::BIGINT / 100.0)::DECIMAL(12,2)",
    "qty"         -> s"(hash($id+4) % 100)::INT",
    "ts"          -> s"make_timestamp(1577836800000000 + ($id % 157680000) * 1000000)",
    "note"        -> s"'note-' || (hash($id+5) % 1000000)::VARCHAR",
    "score"       -> s"((hash($id+6) % 1000)::BIGINT / 10.0)::DOUBLE",
    "flag"        -> s"(hash($id+7) % 2 = 0)"
  )

  val Columns: List[String] = created("i").map(_._1)

  def createTable(catalog: String, n: Long): String =
    s"CREATE TABLE $catalog.s.fact AS SELECT " +
      created("i").map((column, value) => s"$value AS $column").mkString(", ") +
      s" FROM range($n) r(i)"

  /** One side's writes: `k` changed rows or so on `n`. Both sides change some rows by design. */
  def changes(catalog: String, side: String, n: Long, k: Long): List[String] =
    val m = n / k
    if side == "main" then
      List(
        s"UPDATE $catalog.s.fact SET amount = amount + 1 WHERE hash(id) % $m = 0 OR hash(id) % ${m * 20} IN (2, 3) OR hash(id) % ${m * 100} = 6",
        s"DELETE FROM $catalog.s.fact WHERE hash(id) % ${m * 10} = 4",
        s"INSERT INTO $catalog.s.fact SELECT ${n}::BIGINT + 1000000000 + i, 1, 1, 'NEW', 1.00, 1, TIMESTAMP '2030-01-01', 'main-ins', 0.5, true FROM range(${k / 10}) r(i)"
      )
    else
      List(
        s"UPDATE $catalog.s.fact SET status = 'EDITED', qty = qty + 1 WHERE hash(id) % $m = 1",
        s"UPDATE $catalog.s.fact SET amount = amount + 2 WHERE hash(id) % ${m * 20} = 2",
        s"UPDATE $catalog.s.fact SET status = 'EDITED' WHERE hash(id) % ${m * 20} = 3",
        s"DELETE FROM $catalog.s.fact WHERE hash(id) % ${m * 10} = 5 OR hash(id) % ${m * 100} = 6",
        s"INSERT INTO $catalog.s.fact SELECT ${n}::BIGINT + 2000000000 + i, 2, 2, 'NEW', 2.00, 2, TIMESTAMP '2031-01-01', 'br-ins', 0.5, false FROM range(${k / 10}) r(i)"
      )

/** Not part of `sbt test`: cancelled unless `QOD_BENCH_ROWS` is set, and run by
  * `scripts/dev/bench-three-way-merge.sh`. Builds a table, changes it on both sides, runs the real
  * stages and reports seconds, reads and bytes per stage.
  *
  * Environment: `QOD_BENCH_ROWS`, `QOD_BENCH_CHANGES` (changed rows per side), `QOD_BENCH_THREADS`
  * (8), `QOD_BENCH_MEMORY` (16GB), `QOD_BENCH_ROWS_PER_FILE` (a badly maintained table, optional),
  * `QOD_BENCH_DATA_ROOT` (a local temp directory by default, or `s3://bucket/prefix`),
  * `QOD_BENCH_SETUP_SQL` (statements run first, such as a secret; never printed),
  * `QOD_BENCH_VERIFY` (`1`: check the result against a full three-way scan, at
  * `QOD_BENCH_VERIFY_MEMORY`, 48GB by default). What that scan spills, measured under a limit
  * scaled from 48GB at 1B rows: 52 GiB at 200M, 103 GiB at 400M, so about 260 GiB at 1B.
  */
class ThreeWayMergeScaleBench extends AnyFlatSpec with Matchers:
  import ThreeWayMergeScaleBench.{changes, createTable, Columns}

  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  private def statements(conn: Connection, sql: String*): Unit =
    val st = conn.createStatement()
    try sql.foreach(s => st.execute(s))
    finally st.close()

  "the three-way merge" should "report its cost at this scale" in {
    val rows    = env("QOD_BENCH_ROWS").map(_.toLong)
    val changed = env("QOD_BENCH_CHANGES").map(_.toLong)
    assume(rows.nonEmpty && changed.nonEmpty, "QOD_BENCH_ROWS and QOD_BENCH_CHANGES are not set")
    val (n, k)  = (rows.get, changed.get)
    val threads = env("QOD_BENCH_THREADS").getOrElse("8")
    val memory  = env("QOD_BENCH_MEMORY").getOrElse("16GB")
    val workDir = Files.createTempDirectory("qod-bench")
    println(s"work directory: $workDir")
    val root       = env("QOD_BENCH_DATA_ROOT").getOrElse(workDir.resolve("data").toString)
    val run        = java.lang.Long.toHexString(System.nanoTime())
    val mainDb     = s"qodbench_m_$run"
    val branchDb   = s"qodbench_b_$run"
    val mainPath   = s"$root/$mainDb/"
    val branchPath = s"$root/$branchDb/"
    if !root.startsWith("s3://") then
      Files.createDirectories(java.nio.file.Path.of(mainPath))
      Files.createDirectories(java.nio.file.Path.of(branchPath))
    val pg = s"host=${TestPostgres.pgHost} port=${TestPostgres.pgPort} " +
      s"user=${TestPostgres.pgUser} password=${TestPostgres.pgPass}"
    val meta = Map(
      "pgHost"     -> TestPostgres.pgHost,
      "pgPort"     -> TestPostgres.pgPort.toString,
      "pgUser"     -> TestPostgres.pgUser,
      "pgPassword" -> TestPostgres.pgPass,
      "dbName"     -> mainDb,
      "schemaName" -> "main",
      "dataPath"   -> mainPath
    )
    TestPostgres.ensureReachable()
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$mainDb"""")
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$branchDb"""")
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = {
      // Not InProcessDuckDb.properties: its thread budget is the manager's (one async I/O thread), not
      // a node's, and on object storage that alone would decide the numbers.
      val props = new java.util.Properties()
      props.setProperty("secret_directory", InProcessDuckDb.secretDirectory.toString)
      // As every node opens (design section 12): on object storage each statement re-reads every
      // footer, which is what the gate is there to see, and the first one is on by default.
      props.setProperty("enable_external_file_cache", "false")
      props.setProperty("enable_http_metadata_cache", "false")
      DriverManager.getConnection("jdbc:duckdb:", props)
    }
    try
      statements(
        conn,
        "INSTALL ducklake",
        "LOAD ducklake",
        "INSTALL postgres",
        "LOAD postgres",
        s"SET GLOBAL threads = ${Runtime.getRuntime.availableProcessors()}",
        "SET GLOBAL memory_limit = '48GB'",
        s"SET GLOBAL temp_directory = '${workDir.resolve("tmp")}'"
      )
      env("QOD_BENCH_SETUP_SQL").foreach { sql =>
        sql.split(";\n").zipWithIndex.foreach { (statement, i) =>
          // The engine's error can quote the statement, and this one can hold keys.
          Try(statements(conn, statement)).failed.foreach { e =>
            fail(s"QOD_BENCH_SETUP_SQL, statement ${i + 1}: ${e.getClass.getSimpleName}")
          }
        }
      }
      statements(
        conn,
        s"ATTACH 'ducklake:postgres:$pg dbname=$mainDb' AS lake (DATA_PATH '$mainPath')"
      )
      statements(conn, "CREATE SCHEMA lake.s")
      env("QOD_BENCH_ROWS_PER_FILE").foreach { perFile =>
        statements(
          conn,
          s"CALL lake.set_option('parquet_row_group_size', $perFile)",
          "CALL lake.set_option('target_file_size', '1KB')"
        )
      }
      val generated = System.nanoTime()
      statements(conn, createTable("lake", n))
      env("QOD_BENCH_ROWS_PER_FILE").foreach { _ =>
        statements(
          conn,
          "CALL lake.set_option('target_file_size', '512MB')",
          "CALL lake.set_option('parquet_row_group_size', 122880)"
        )
      }
      println(f"generated $n rows in ${(System.nanoTime() - generated) / 1e9}%.0f s")

      val parent = DuckLakeCatalogReader(meta)
      try
        val fork = BranchCloner(meta)
          .clone(mainDb, branchDb, branchPath)
          .fold(e => fail(e), _.forkSnapshot)
        statements(
          conn,
          s"ATTACH 'ducklake:postgres:$pg dbname=$branchDb' AS qod_branch (DATA_PATH '$branchPath')"
        )
        statements(conn, changes("lake", "main", n, k)*)
        statements(conn, changes("qod_branch", "branch", n, k)*)
        val br = DuckLakeCatalogReader(meta.updated("dbName", branchDb))
        try
          val cs     = BranchChanges.compute(br, parent, fork)
          val tables =
            RowMergeTable.plan(cs, br, parent, Map.empty).fold(p => fail(p.toString), identity)
          statements(conn, s"SET GLOBAL threads = $threads", s"SET GLOBAL memory_limit = '$memory'")

          // Timings per stage, with the file-system log on disk: reading it in memory while spilling
          // deadlocks on v2.0.0. Each stage's first read is a marker file, which timestamps it.
          val logDir  = Files.createDirectories(workDir.resolve("io"))
          val markers = Files.createDirectories(workDir.resolve("markers"))
          statements(
            conn,
            s"CALL enable_logging('FileSystem', storage='file', storage_config={'path':'$logDir'})"
          )
          val node = new JdbcMergeNode(conn)
          // The writes above create conflicts by design (the `% ${m * 20} = 2` rows): main wins them.
          val policy = Some(ConflictPolicy.Main)
          val runner = new RowMergeRunner("lake", BranchMergeSql.BranchAlias, fork, policy)
          val report = ListBuffer.empty[(String, Double)]
          def stage[A](name: String)(io: IO[Either[String, A]]): A =
            Files.writeString(markers.resolve(name), "x")
            statements(conn, s"SELECT content FROM read_text('${markers.resolve(name)}')")
            val started = System.nanoTime()
            val out     = io.unsafeRunSync().fold(e => fail(s"$name: $e"), identity)
            report += name -> ((System.nanoTime() - started) / 1e9)
            out
          stage("prepare")(runner.prepare(node, tables))
          stage("warm_up")(runner.warmUp(node, tables))
          val counts = stage("decide")(runner.decide(node, tables))
          stage("apply")(
            node.exec(
              BranchMergeSql.applyBatch(
                parentAlias = "lake",
                branchAlias = BranchMergeSql.BranchAlias,
                tableChanges = cs.tables,
                rowMerges = tables.zip(counts),
                ranged = false,
                updateChunkRows = env("QOD_BRANCH_MERGE_UPDATE_CHUNK_ROWS")
                  .map(_.toLong)
                  .getOrElse(BranchingConfig().mergeUpdateChunkRows),
                fork = fork,
                head = cs.headSnapshot,
                author = "bench",
                message = s"bench [$run]"
              )
            )
          )
          statements(conn, "CALL disable_logging()")

          println(s"counts: $counts")
          val io = Using.resource(DriverManager.getConnection("jdbc:duckdb:")) { reader =>
            val rs = reader
              .createStatement()
              .executeQuery(
                s"""WITH e AS (SELECT timestamp::TIMESTAMP AS ts,
                   |    regexp_extract(message, '"path":"([^"]*)"', 1) AS path,
                   |    regexp_extract(message, '"op":"([^"]*)"', 1) AS op,
                   |    try_cast(regexp_extract(message, '"bytes":"?([0-9]+)', 1) AS BIGINT) AS bytes
                   |  FROM read_csv('$logDir/duckdb_log_entries.csv', header=true, all_varchar=true,
                   |    quote='"', escape='"', max_line_size=10000000)),
                   |mk AS (SELECT regexp_extract(path, '.*/markers/(.*)', 1) AS step, min(ts) AS ts
                   |  FROM e WHERE path LIKE '%/markers/%' GROUP BY 1)
                   |SELECT mk.step,
                   |  count(*) FILTER (op = 'READ' AND path NOT LIKE '%duckdb_temp_%') AS reads,
                   |  coalesce(sum(bytes) FILTER (op = 'READ' AND path NOT LIKE '%duckdb_temp_%'), 0) AS bytes,
                   |  count(*) FILTER (op = 'WRITE' AND path NOT LIKE '%duckdb_temp_%') AS writes,
                   |  coalesce(sum(bytes) FILTER (op = 'WRITE' AND path LIKE '%duckdb_temp_%'), 0) AS spill
                   |FROM e ASOF JOIN mk ON e.ts >= mk.ts
                   |WHERE path NOT LIKE '%/markers/%'
                   |GROUP BY mk.step""".stripMargin
              )
            val out = scala.collection.mutable.Map.empty[String, (Long, Long, Long, Long)]
            while rs.next() do
              out(rs.getString(1)) = (rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))
            out.toMap
          }
          println(
            f"${"stage"}%-10s ${"seconds"}%9s ${"reads"}%9s ${"GB read"}%9s ${"writes"}%9s ${"GB spill"}%9s"
          )
          report.foreach { (name, seconds) =>
            val (r, b, w, s) = io.getOrElse(name, (0L, 0L, 0L, 0L))
            println(f"$name%-10s $seconds%9.1f $r%9d ${b / 1e9}%9.2f $w%9d ${s / 1e9}%9.2f")
          }
          println(f"total seconds: ${report.map(_._2).sum}%.1f")

          if env("QOD_BENCH_VERIFY").contains("1") then
            statements(
              conn,
              // The stages are reported, and the scan reads none of their tables. Kept, their spill
              // would count against DuckDB's cap, fixed at the first spill as 90% of the free disk:
              // on a large run, the difference between a verdict and none.
              """DROP SCHEMA "memory"."qod_merge" CASCADE""",
              "SET GLOBAL threads = " + Runtime.getRuntime.availableProcessors(),
              s"SET GLOBAL memory_limit = '${env("QOD_BENCH_VERIFY_MEMORY").getOrElse("48GB")}'"
            )
            val verdict = ScanOracle
              .statements("s", "fact", Columns, fork, cs.mainSnapshot, cs.headSnapshot, policy)
            statements(conn, verdict.dropRight(2)*)
            val checks = verdict.takeRight(2).map { sql =>
              val rs = conn.createStatement().executeQuery(sql)
              rs.next()
              s"${rs.getString(1)},${rs.getLong(2)}"
            }
            println(s"full scan: $checks")
            checks shouldBe List("kept,0", "post_fork,0")
        finally br.close()
      finally parent.close()
    finally
      conn.close()
      Try(TestPostgres.dropDatabase(mainDb))
      Try(TestPostgres.dropDatabase(branchDb))
      Try {
        val tree = Files.walk(workDir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }
  }
