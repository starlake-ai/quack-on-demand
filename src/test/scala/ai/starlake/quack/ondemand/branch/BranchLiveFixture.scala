package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.testkit.{PostgresFixture, TestPostgres}
import org.scalatest.Assertions

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.sys.process._

/** Live-fixture helpers for the branch specs: a second Postgres database for the clone, a sibling
  * data prefix, and a duckdb CLI runner that attaches the parent as `lake` and the branch as
  * `qod_branch` (the merge alias), returning CSV rows.
  */
trait BranchLiveFixture extends PostgresFixture:

  protected def parentMeta: Map[String, String] =
    Map(
      "pgHost"     -> TestPostgres.pgHost,
      "pgPort"     -> TestPostgres.pgPort.toString,
      "pgUser"     -> TestPostgres.pgUser,
      "pgPassword" -> TestPostgres.pgPass,
      "dbName"     -> currentDbName.getOrElse(Assertions.fail("outside withCatalog")),
      "schemaName" -> "main"
    )

  protected def branchMeta(branchDb: String): Map[String, String] =
    parentMeta.updated("dbName", branchDb)

  protected def adminSql(sql: String): Unit =
    val c =
      DriverManager.getConnection(TestPostgres.adminUrl, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val st = c.createStatement()
      try st.execute(sql)
      finally st.close()
    finally c.close()

  /** Runs `body` with a fresh empty branch database and a sibling data prefix, dropping both. */
  protected def withBranchDb[A](test: (String, Path) => A): A =
    val parentDb  = currentDbName.getOrElse(Assertions.fail("outside withCatalog"))
    val branchDb  = s"${parentDb}__br_${System.nanoTime().toHexString.takeRight(8)}"
    val parentDir = currentDataPath.getOrElse(Assertions.fail("outside withCatalog"))
    val branchDir = parentDir.resolveSibling(parentDir.getFileName.toString + "__br")
    adminSql(s"""CREATE DATABASE "$branchDb"""")
    try
      Files.createDirectories(branchDir)
      test(branchDb, branchDir)
    finally
      TestPostgres.dropDatabase(branchDb)
      if Files.exists(branchDir) then
        Files
          .walk(branchDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p))

  protected def branchReader(branchDb: String): DuckLakeCatalogReader =
    DuckLakeCatalogReader(branchMeta(branchDb))

  protected def parentReader: DuckLakeCatalogReader = DuckLakeCatalogReader(parentMeta)

  private def attachPrelude(branchDb: Option[String], branchDir: Option[Path]): String =
    val m         = parentMeta
    val parentDir = currentDataPath.getOrElse(Assertions.fail("outside withCatalog"))
    val base      =
      s"""INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres;
         |ATTACH 'ducklake:postgres:host=${m("pgHost")} port=${m("pgPort")} dbname=${m(
          "dbName"
        )} user=${m(
          "pgUser"
        )} password=${m("pgPassword")}' AS lake (DATA_PATH '$parentDir/');
         |""".stripMargin
    (branchDb, branchDir) match
      case (Some(db), Some(dir)) =>
        base +
          s"""ATTACH 'ducklake:postgres:host=${m("pgHost")} port=${m("pgPort")} dbname=$db user=${m(
              "pgUser"
            )} password=${m("pgPassword")}' AS ${BranchMergeSql.BranchAlias} (DATA_PATH '$dir/');
             |""".stripMargin
      case _ => base

  /** Runs SQL in a fresh duckdb CLI session with `lake` (and optionally `qod_branch`) attached;
    * returns stdout as CSV lines without header. Fails the test on a non-zero exit.
    */
  protected def duck(
      sql: String,
      branchDb: Option[String] = None,
      branchDir: Option[Path] = None
  ): List[String] =
    val script = attachPrelude(branchDb, branchDir) + sql + "\n"
    val tmp    = Files.createTempFile("branch-live", ".sql")
    Files.writeString(tmp, script)
    val out = new StringBuilder
    val err = new StringBuilder
    try
      val rc = (Process(Seq("duckdb", "-csv", "-noheader")) #< tmp.toFile) ! ProcessLogger(
        l => out.append(l).append('\n'),
        l => err.append(l).append('\n')
      )
      assert(rc == 0, s"duckdb exit=$rc\nstderr:\n$err\nstdout:\n$out\nscript:\n$script")
      if err.nonEmpty then Assertions.fail(s"duckdb reported errors:\n$err\nscript:\n$script")
      out.toString.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    finally Files.deleteIfExists(tmp)
