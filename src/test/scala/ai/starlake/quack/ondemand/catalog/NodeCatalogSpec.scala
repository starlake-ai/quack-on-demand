package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{PoolKey, TenantDbKind}
import ai.starlake.quack.ondemand.InProcessDuckDb
import ai.starlake.quack.ondemand.api.{CatalogColumnEntry, CatalogSchemaEntry, CatalogTableEntry}
import cats.effect.IO
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.{DriverManager, ResultSet}

class NodeCatalogSpec extends AnyFlatSpec with Matchers:

  Class.forName("org.duckdb.DuckDBDriver")

  /** A JDBC row rendered the way `ArrowRowsDecoder` renders an Arrow cell: numbers as JSON numbers,
    * booleans as booleans, everything else as strings.
    */
  private def rows(rs: ResultSet): List[List[Json]] =
    val n   = rs.getMetaData.getColumnCount
    val out = List.newBuilder[List[Json]]
    while rs.next() do
      out += (1 to n).toList.map { i =>
        rs.getObject(i) match
          case null                  => Json.Null
          case b: java.lang.Boolean  => Json.fromBoolean(b)
          case num: java.lang.Number => Json.fromBigDecimal(BigDecimal(num.toString))
          case other                 => Json.fromString(other.toString)
      }
    out.result()

  /** An attached `.db` file under an alias that needs quoting, the way a node attaches a
    * `duckdb-file` database, plus a table in DuckDB's own `memory` catalog.
    */
  private def withNode(test: (String => List[List[Json]]) => Unit): Unit =
    val dir  = Files.createTempDirectory("qod-node-catalog")
    val conn = DriverManager.getConnection(InProcessDuckDb.url(), InProcessDuckDb.properties)
    try
      val st = conn.createStatement()
      List(
        s"ATTACH '${dir.resolve("f.db")}' AS \"it's db\"",
        "CREATE SCHEMA \"it's db\".sales",
        "CREATE TABLE \"it's db\".sales.orders(id INTEGER PRIMARY KEY, amt DOUBLE NOT NULL, note VARCHAR)",
        "INSERT INTO \"it's db\".sales.orders VALUES (1, 2.5, 'a'), (2, 3, 'b')",
        "CREATE TABLE \"it's db\".main.pairs(a INT, b INT, c INT, PRIMARY KEY (a, b))",
        "CREATE TABLE memory.main.scratch(x INT)"
      ).foreach(st.execute)
      test(sql => rows(conn.createStatement().executeQuery(sql)))
    finally conn.close()

  "schemasSql" should "list every schema of the attached file with its table count" in withNode {
    run =>
      NodeCatalog.schemaRows(run(NodeCatalog.schemasSql("it's db"))) shouldBe
        List(CatalogSchemaEntry("main", 1), CatalogSchemaEntry("sales", 1))
  }

  it should "read DuckDB's memory catalog for a memory database" in withNode { run =>
    val alias = NodeCatalog.aliasFor(TenantDbKind.InMemory, "ignored")
    NodeCatalog.schemaRows(run(NodeCatalog.schemasSql(alias))) shouldBe
      List(CatalogSchemaEntry("main", 1))
  }

  "tablesSql" should "list one schema's tables with their estimated row count" in withNode { run =>
    NodeCatalog.tableRows(run(NodeCatalog.tablesSql("it's db", "sales"))) shouldBe
      List(CatalogTableEntry("sales", "orders", 2L, 0, None))
    NodeCatalog.tableRows(
      run(NodeCatalog.tablesSql("it's db", "sales", Some("ghost")))
    ) shouldBe Nil
  }

  "columnsSql" should "list columns in order with nullability and the primary key" in withNode {
    run =>
      NodeCatalog.columnRows(run(NodeCatalog.columnsSql("it's db", "sales", "orders"))) shouldBe
        List(
          CatalogColumnEntry(1, "id", "INTEGER", nullable = false, isPrimaryKey = true),
          CatalogColumnEntry(2, "amt", "DOUBLE", nullable = false, isPrimaryKey = false),
          CatalogColumnEntry(3, "note", "VARCHAR", nullable = true, isPrimaryKey = false)
        )
      NodeCatalog
        .columnRows(run(NodeCatalog.columnsSql("it's db", "main", "pairs")))
        .map(c => c.name -> c.isPrimaryKey) shouldBe List("a" -> true, "b" -> true, "c" -> false)
  }

  private val filesPool = PoolKey("acme", "acme_files", "bi")

  private def routed(
      query: (PoolKey, String) => IO[Either[RouterFailure, List[List[Json]]]],
      pool: Option[PoolKey] = Some(filesPool),
      timeoutSec: Int = 5
  ) = new RoutedNodeCatalog((_, _) => pool, (_, _) => "filesdb", query, timeoutSec)

  "RoutedNodeCatalog" should "be NoPool without a pool, without querying" in {
    var queried = false
    val nc      = routed((_, _) => { queried = true; IO.pure(Right(Nil)) }, pool = None)
    nc.listSchemas("acme", "acme_files") shouldBe Left(NodeCatalogFailure.NoPool)
    queried shouldBe false
  }

  it should "run on the picked pool against the database's catalog alias" in {
    var seen: List[(PoolKey, String)] = Nil
    val nc                            = routed { (k, sql) =>
      seen = seen :+ (k -> sql)
      IO.pure(Right(List(List(Json.fromString("main"), Json.fromInt(3)))))
    }
    nc.listSchemas("acme", "acme_files") shouldBe Right(List(CatalogSchemaEntry("main", 3)))
    seen.map(_._1) shouldBe List(filesPool)
    seen.head._2 should include("s.database_name = 'filesdb'")
  }

  it should "surface a router failure and a timeout" in {
    routed((_, _) => IO.pure(Left(RouterFailure.Unavailable("pool is resuming"))))
      .listTables("acme", "acme_files", "main") shouldBe
      Left(NodeCatalogFailure.Failed("pool is resuming"))
    routed((_, _) => IO.never, timeoutSec = 1).listTables("acme", "acme_files", "main") shouldBe
      Left(NodeCatalogFailure.TimedOut)
  }

  it should "answer None for a missing table without reading its columns" in {
    var calls = 0
    val nc    = routed { (_, _) => calls += 1; IO.pure(Right(Nil)) }
    nc.getTable("acme", "acme_files", "main", "ghost") shouldBe Right(None)
    calls shouldBe 1
  }
