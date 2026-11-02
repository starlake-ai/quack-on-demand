package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.InProcessDuckDb
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.duckdb.DuckDBConnection
import org.scalatest.Assertions

import java.nio.file.Path
import java.sql.{Connection, DriverManager, ResultSet}
import scala.collection.mutable.ListBuffer

/** A merge node stand-in: one in-process DuckDB, a new connection per call, as the real node serves
  * each request. So working tables must survive between calls and session settings must not, which
  * is what the real thing does. `beforeCommit` runs just before a batch's COMMIT, its transaction
  * open: what another client does at that moment.
  */
final class JdbcMergeNode(root: Connection, beforeCommit: () => Unit = () => ()) extends MergeNode:

  private def on[A](body: Connection => A): IO[Either[String, A]] = IO.blocking {
    val c = root.asInstanceOf[DuckDBConnection].duplicate()
    try Right(body(c))
    catch case e: java.sql.SQLException => Left(e.getMessage)
    finally c.close()
  }

  def exec(batch: String): IO[Either[String, Unit]] = on { c =>
    val st = c.createStatement()
    try
      batch.split(";\n").map(_.stripSuffix(";").trim).filter(_.nonEmpty).foreach { s =>
        if s == "COMMIT" then beforeCommit()
        st.execute(s)
      }
    finally st.close()
  }

  def rows(sql: String): IO[Either[String, List[List[Json]]]] = on { c =>
    val st = c.createStatement()
    try
      val rs  = st.executeQuery(sql)
      val out = ListBuffer.empty[List[Json]]
      while rs.next() do out += JdbcMergeNode.row(rs)
      out.toList
    finally st.close()
  }

object JdbcMergeNode:
  def row(rs: ResultSet): List[Json] =
    (1 to rs.getMetaData.getColumnCount).toList.map { i =>
      rs.getObject(i) match
        case null                    => Json.Null
        case n: java.lang.Long       => Json.fromLong(n)
        case n: java.lang.Integer    => Json.fromInt(n)
        case n: java.math.BigInteger => Json.fromBigInt(BigInt(n))
        case other                   => Json.fromString(other.toString)
    }

/** The oracle: what main must hold after a merge, computed by an independent full three-way scan of
  * base, main and branch, the slow way the merge exists to avoid. Each statement is plain SQL over
  * the attached `lake` and `qod_branch`. The last two answer `(check, wrong rows)`.
  */
object ScanOracle:
  private def q(i: String) = "\"" + i.replace("\"", "\"\"") + "\""

  def statements(
      schema: String,
      table: String,
      columns: List[String],
      fork: Long,
      ours: Long,
      theirs: Long,
      policy: Option[ConflictPolicy]
  ): List[String] =
    val t                = s"${q(schema)}.${q(table)}"
    val cols             = columns.map(q)
    val list             = cols.mkString(", ")
    def rowOf(a: String) = cols.map(c => s"$a.$c").mkString("(", ", ", ")")
    val branchWins       = policy.contains(ConflictPolicy.Branch)
    val winner           = if branchWins then "th" else "ou"
    val picks            = cols
      .map(c =>
        s"CASE WHEN th.r IS NULL THEN ou.$c WHEN ou.r IS NULL THEN th.$c " +
          s"WHEN th.$c IS NOT DISTINCT FROM ba.$c THEN ou.$c " +
          s"WHEN ou.$c IS NOT DISTINCT FROM ba.$c THEN th.$c " +
          s"WHEN ou.$c IS NOT DISTINCT FROM th.$c THEN th.$c " +
          s"ELSE $winner.$c END AS $c"
      )
      .mkString(", ")
    val fate =
      "CASE WHEN ou.r IS NULL AND th.r IS NULL THEN 'gone' " +
        s"WHEN ou.r IS NULL AND ${rowOf("th")} IS NOT DISTINCT FROM ${rowOf("ba")} THEN 'gone' " +
        s"WHEN ou.r IS NULL THEN ${if branchWins then "'returned'" else "'gone'"} " +
        s"WHEN th.r IS NULL AND ${rowOf("ou")} IS NOT DISTINCT FROM ${rowOf("ba")} THEN 'gone' " +
        s"WHEN th.r IS NULL THEN ${if branchWins then "'gone'" else "'kept'"} " +
        "ELSE 'kept' END AS fate"
    val postFork =
      s"(SELECT $list FROM scan_ours WHERE r >= getvariable('n') " +
        s"UNION ALL SELECT $list FROM scan_theirs WHERE r >= getvariable('n') " +
        s"UNION ALL SELECT $list FROM scan_expected WHERE fate = 'returned')"
    val actualPost = s"(SELECT $list FROM scan_after WHERE r >= getvariable('n'))"
    List(
      s"SET VARIABLE n = (SELECT coalesce(max(rowid), -1) + 1 FROM lake.$t AT (VERSION => $fork))",
      s"CREATE OR REPLACE TEMP TABLE scan_base AS SELECT rowid AS r, $list FROM lake.$t AT (VERSION => $fork)",
      s"CREATE OR REPLACE TEMP TABLE scan_ours AS SELECT rowid AS r, $list FROM lake.$t AT (VERSION => $ours)",
      s"CREATE OR REPLACE TEMP TABLE scan_theirs AS SELECT rowid AS r, $list FROM qod_branch.$t AT (VERSION => $theirs)",
      s"CREATE OR REPLACE TEMP TABLE scan_after AS SELECT rowid AS r, $list FROM lake.$t",
      "CREATE OR REPLACE TEMP TABLE scan_expected AS " +
        s"SELECT ba.r, $fate, $picks FROM scan_base ba " +
        "LEFT JOIN scan_ours ou ON ba.r = ou.r LEFT JOIN scan_theirs th ON ba.r = th.r",
      "SELECT 'kept', count(*) FROM scan_expected e " +
        "FULL JOIN (SELECT * FROM scan_after WHERE r < getvariable('n')) a ON e.r = a.r " +
        s"WHERE e.r IS NULL OR (e.fate = 'kept' AND (a.r IS NULL OR ${rowOf("e")} IS DISTINCT FROM ${rowOf("a")})) " +
        "OR (e.fate <> 'kept' AND a.r IS NOT NULL)",
      s"SELECT 'post_fork', count(*) FROM (($actualPost EXCEPT ALL $postFork) " +
        s"UNION ALL ($postFork EXCEPT ALL $actualPost))"
    )

/** The live side of the three-way merge specs: real catalogs, real engine, every stage. */
trait ThreeWayMergeFixture extends BranchLiveFixture:

  protected def cloneBranch(branchDb: String, branchDir: Path): Long =
    BranchCloner(parentMeta)
      .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
      .toOption
      .get
      .forkSnapshot

  /** A node with both catalogs attached the way `PoolSupervisor.mergeNodeSpec` attaches them. */
  protected def withNode[A](
      branchDb: String,
      branchDir: Path,
      beforeCommit: () => Unit = () => ()
  )(body: MergeNode => A): A =
    Class.forName("org.duckdb.DuckDBDriver")
    val m                  = parentMeta
    val parentDir          = currentDataPath.getOrElse(Assertions.fail("outside withCatalog"))
    def attach(db: String) =
      s"ducklake:postgres:host=${m("pgHost")} port=${m("pgPort")} dbname=$db " +
        s"user=${m("pgUser")} password=${m("pgPassword")}"
    val root = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    try
      val st = root.createStatement()
      try
        List(
          "INSTALL ducklake",
          "LOAD ducklake",
          "INSTALL postgres",
          "LOAD postgres",
          s"ATTACH '${attach(m("dbName"))}' AS lake (DATA_PATH '$parentDir/')",
          s"ATTACH '${attach(branchDb)}' AS ${BranchMergeSql.BranchAlias} " +
            s"(DATA_PATH '$branchDir/', READ_ONLY)"
        ).foreach(s => st.execute(s))
      finally st.close()
      body(new JdbcMergeNode(root, beforeCommit))
    finally root.close()

  protected def planned(
      cs: BranchChangeSet,
      br: DuckLakeCatalogReader,
      main: DuckLakeCatalogReader,
      keys: Map[String, List[String]] = Map.empty
  ): List[RowMergeTable] =
    RowMergeTable.plan(cs, br, main, keys).fold(p => Assertions.fail(p.toString), identity)

  /** Prepare, Decide and Apply on one node: the counts, or Apply's failure. `claimedUpdateRows`
    * tells Apply that an update holds that many rows, which only splits it into more ranges, so
    * that a small table gets the several statements of a large update.
    */
  protected def runMerge(
      node: MergeNode,
      cs: BranchChangeSet,
      br: DuckLakeCatalogReader,
      main: DuckLakeCatalogReader,
      message: String,
      policy: Option[ConflictPolicy] = None,
      keys: Map[String, List[String]] = Map.empty,
      ranged: Boolean = false,
      claimedUpdateRows: Option[Long] = None
  ): Either[String, List[RowCounts]] =
    val tables = planned(cs, br, main, keys)
    val runner = new RowMergeRunner("lake", BranchMergeSql.BranchAlias, cs.forkSnapshot, policy)
    (for
      _      <- EitherT(runner.prepare(node, tables))
      counts <- EitherT(runner.decide(node, tables))
      _      <- EitherT(
        node.exec(
          BranchMergeSql.applyBatch(
            parentAlias = "lake",
            branchAlias = BranchMergeSql.BranchAlias,
            tableChanges = cs.tables,
            rowMerges = tables.zip(
              counts.map(c =>
                claimedUpdateRows.filter(_ => c.toUpdate > 0L).fold(c)(n => c.copy(toUpdate = n))
              )
            ),
            ranged = ranged,
            updateChunkRows = 1000000L,
            fork = cs.forkSnapshot,
            head = cs.headSnapshot,
            author = BranchMergeSql.author("acme", "bob"),
            message = message
          )
        )
      )
    yield counts).value.unsafeRunSync()

  /** The oracle's two checks as `check,wrong rows` lines. */
  protected def scanCheck(
      schema: String,
      table: String,
      columns: List[String],
      fork: Long,
      ours: Long,
      theirs: Long,
      policy: Option[ConflictPolicy],
      branchDb: String,
      branchDir: Path
  ): List[String] =
    duck(
      ScanOracle
        .statements(schema, table, columns, fork, ours, theirs, policy)
        .mkString("", ";\n", ";"),
      Some(branchDb),
      Some(branchDir)
    )

  protected val AllRight: List[String] = List("kept,0", "post_fork,0")
