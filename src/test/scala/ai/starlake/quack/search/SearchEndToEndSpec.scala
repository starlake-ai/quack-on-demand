package ai.starlake.quack.search

import ai.starlake.quack.it.authz.AuthzStack
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, SQLException}
import java.util.Properties
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** Task 18 (spec 12 Phase 1 item 22): row and column policies for search, end to end, on a real
  * manager ([[AuthzStack]]: local runtime, the real spawn script, the qod-node search build from
  * `QOD_NODE_BIN`, a throwaway control plane) through Flight SQL (the Flight SQL JDBC driver, TLS)
  * and the native Quack front door (the DuckDB 2.0 CLI's `ATTACH 'quack:...'`, each statement
  * through `quack_query_by_name`). The matrix is [[SearchEndToEndCases]]; the rows that need a
  * session or a sequence (transactions, batches, the decide counter) are here.
  *
  * World: tenant `searche2ex<run>`, a DuckLake database `lake` (pool `bi`) holding `pages` (indexed
  * and built, then the rows with `f10 = 5` deleted, so `AT (VERSION => v0)` differs from now),
  * `pages_rw` (indexed and built; `plain`, `rls10` and `clsmask` hold RW on it) and `pages_behind`
  * (indexed, never built, more rows than the node's `maxUnindexedRows`). Users: `plain` (read
  * grants), `rls10` (row policy `f10 = 0` on the three tables), `clsmask` (mask on `title`),
  * `clssearch` (mask on `pages.body`, the search column), `both` (row policy and mask), `other` (no
  * grant), `admin` (superuser).
  *
  * Cancelled without the test Postgres or a qod-node build; the front door rows also without a
  * DuckDB 2.0 CLI on PATH.
  */
class SearchEndToEndSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  import SearchEndToEndCases.*

  private val run    = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Tenant = s"searche2ex$run"
  private val Cap    = 20
  private val Behind = 50

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script and kill-quack-nodes.sh")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else None

  private lazy val root: Path    = Files.createTempDirectory("qod-search-e2e-files").toRealPath()
  private lazy val work: Path    = Files.createTempDirectory("qod-search-e2e-work").toRealPath()
  private lazy val certDir: Path = Files.createTempDirectory("qod-search-e2e-certs").toRealPath()

  private var stack: AuthzStack = null
  private var lake: String      = "" // the tenant-db, also its catalog alias
  private var world: World      = null
  private val connections       = mutable.Map.empty[User, Connection]

  private def password(u: User): String = if u == User.Admin then "admin" else s"${u.login}-se2e-pw"

  // ---------- setup ----------

  override def beforeAll(): Unit =
    if missing.isEmpty then
      stack = new AuthzStack(
        "search_e2e",
        20991,
        31391,
        29591,
        20993,
        23440,
        23449,
        filesRoot = root,
        stackPrefixes = List("searche2ex"),
        extraManagerConfig = s"search.maxUnindexedRows = $Cap",
        flightTls = Some(certDir)
      )
      stack.boot()
      provision()

  override def afterAll(): Unit =
    try connections.values.foreach(c => Try(c.close()))
    finally
      try if stack != null then stack.stop()
      finally
        List(root, work, certDir).foreach { d =>
          Try {
            val tree = Files.walk(d)
            try
              tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
            finally tree.close()
          }
        }

  private def obj(fields: (String, Json)*): String = Json.obj(fields*).noSpaces
  private def s(v: String): Json                   = Json.fromString(v)

  private def admin(sql: String): List[List[String]] =
    flight(User.Admin, sql)
      .fold(e => throw new IllegalStateException(s"admin: $sql -> $e"), identity)

  private def provision(): Unit =
    stack.post(
      "/api/tenant/create",
      obj("id" -> s(Tenant), "displayName" -> s(Tenant), "metastore" -> Json.obj())
    )
    lake = stack
      .post(
        "/api/database/create",
        obj(
          "tenant"   -> s(Tenant),
          "name"     -> s("lake"),
          "kind"     -> s("ducklake"),
          "dataPath" -> s(s"$work/lake/")
        )
      )
      .hcursor
      .get[String]("name")
      .fold(e => throw e, identity)
    stack.catalogDatabases += lake
    stack.post(
      "/api/pool/create",
      s"""{"tenant":"$Tenant","tenantDb":"$lake","pool":"bi","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
    )
    stack.await("pool bi serving", 120.seconds)(flight(User.Admin, "SELECT 1").toOption)
    val ddl = "(id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER, cat VARCHAR)"
    List("pages" -> 120, "pages_rw" -> 40, "pages_behind" -> Behind).foreach { (t, n) =>
      admin(s"CREATE TABLE $lake.main.$t $ddl")
      admin(s"INSERT INTO $lake.main.$t VALUES ${valuesOf(0 until n)}")
      stack.post(
        "/api/search/index/create",
        obj(
          "tenant"   -> s(Tenant),
          "database" -> s(lake),
          "table"    -> s(t),
          "columns"  -> Json.arr(s("body"))
        )
      )
    }
    // pages_behind is never built: every search of it indexes its rows itself, past the cap.
    List("pages", "pages_rw").foreach { t =>
      stack.post(
        "/api/search/index/build",
        obj("tenant" -> s(Tenant), "database" -> s(lake), "table" -> s(t), "pool" -> s("bi")),
        timeoutSec = 120
      )
    }
    val List(v0, v0Time) = admin(
      s"SELECT snapshot_id, snapshot_time::VARCHAR FROM __ducklake_metadata_$lake.ducklake_snapshot " +
        "ORDER BY snapshot_id DESC LIMIT 1"
    ).head: @unchecked
    admin(s"DELETE FROM $lake.main.pages WHERE f10 = 5")
    provisionUsers()
    world = World(
      lake,
      docs(s"$lake.main.pages"),
      docs(s"$lake.main.pages AT (VERSION => $v0)"),
      v0.toLong,
      v0Time,
      Behind,
      Cap
    )
    require(world.pagesAtV0.size == 120 && world.pagesNow.size == 108, "the delete did not land")

  /** Every live row of `from`, as a superuser's plain SELECT reads it. */
  private def docs(from: String): List[Doc] =
    admin(s"SELECT rowid, id, title, body, f10, cat FROM $from ORDER BY rowid").map {
      case List(rowid, id, title, body, f10, cat) =>
        Doc(rowid.toLong, id.toLong, title, body, f10.toInt, cat)
      case other => throw new IllegalStateException(s"unexpected row $other")
    }

  private def createRole(name: String): String =
    stack
      .post("/api/role/create", obj("tenant" -> s(Tenant), "name" -> s(name)))
      .hcursor
      .get[String]("id")
      .fold(e => throw e, identity)

  private def grant(role: String, table: String, verb: String): Unit =
    stack.post(
      "/api/role/permission/grant",
      obj(
        "roleId"  -> s(role),
        "catalog" -> s(lake),
        "schema"  -> s("main"),
        "table"   -> s(table),
        "verb"    -> s(verb)
      )
    )

  private def mask(role: String, table: String, column: String): Unit =
    stack.post(
      "/api/role/column-policy/create",
      obj(
        "roleId"       -> s(role),
        "catalogName"  -> s(lake),
        "schemaName"   -> s("main"),
        "tableName"    -> s(table),
        "columnName"   -> s(column),
        "action"       -> s("mask"),
        "transformSql" -> s(s"'$Mask'")
      )
    )

  private def rowPolicy(role: String, table: String): Unit =
    stack.post(
      "/api/role/row-policy/create",
      obj(
        "roleId"       -> s(role),
        "catalogName"  -> s(lake),
        "schemaName"   -> s("main"),
        "tableName"    -> s(table),
        "predicateSql" -> s("f10 = 0")
      )
    )

  private def provisionUsers(): Unit =
    def user(u: User, roles: List[String]): Unit =
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Tenant),
          "username" -> s(u.login),
          "password" -> s(password(u)),
          "kind"     -> s("user"),
          "roles"    -> Json.arr(roles.map(s)*),
          "groups"   -> Json.arr(s("qod_all_pools"))
        )
      )
    def role(u: User)(setup: String => Unit): Unit =
      val name = s"se2e_${u.login}"
      setup(createRole(name))
      user(u, List(name))
    role(User.Plain) { r =>
      grant(r, "pages", "RO"); grant(r, "pages_behind", "RO"); grant(r, "pages_rw", "RW")
    }
    role(User.Rls10) { r =>
      grant(r, "pages", "RO"); grant(r, "pages_behind", "RO"); grant(r, "pages_rw", "RW")
      List("pages", "pages_rw", "pages_behind").foreach(rowPolicy(r, _))
    }
    role(User.ClsMask) { r =>
      grant(r, "pages", "RO"); grant(r, "pages_behind", "RO"); grant(r, "pages_rw", "RW")
      mask(r, "pages", "title"); mask(r, "pages_rw", "title")
    }
    role(User.ClsSearch) { r =>
      grant(r, "pages", "RO"); grant(r, "pages_behind", "RO")
      mask(r, "pages", "body")
    }
    role(User.Both) { r =>
      grant(r, "pages", "RO"); grant(r, "pages_behind", "RO")
      rowPolicy(r, "pages"); rowPolicy(r, "pages_behind")
      mask(r, "pages", "title")
    }
    user(User.Other, List("qod_no_tables"))

  // ---------- the two edges ----------

  private def ready(): Unit = assume(missing.isEmpty, missing.getOrElse(""))

  /** A refusal as the client got it: its Flight status code (when the driver carries one) and the
    * whole message chain.
    */
  final case class Failure(code: Option[String], message: String)

  /** `u`'s JDBC connection over TLS (the edge's own self-signed certificate, not verified). */
  private def connection(u: User): Connection =
    connections.getOrElseUpdate(
      u, {
        Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver")
        val props = new Properties()
        props.setProperty("user", u.login)
        props.setProperty("password", password(u))
        val url = s"jdbc:arrow-flight-sql://127.0.0.1:${stack.flightPort}/?useEncryption=true" +
          s"&disableCertificateVerification=true&tenant=$Tenant&pool=bi" +
          (if u == User.Admin then "&superuser=true" else "")
        DriverManager.getConnection(url, props)
      }
    )

  /** The status code a Flight failure carries, read from wherever the driver keeps it in the cause
    * chain (its classes are shaded, so by method name).
    */
  private def statusOf(e: Throwable): Option[String] =
    Iterator
      .iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .take(12)
      .flatMap { t =>
        List("status", "getStatus").iterator.flatMap { m =>
          Try(t.getClass.getMethod(m).invoke(t)).toOption.flatMap { st =>
            List("code", "getCode").iterator
              .flatMap(c => Try(st.getClass.getMethod(c).invoke(st)).toOption)
              .map(String.valueOf)
              .nextOption()
          }
        }
      }
      .nextOption()

  /** The refusal's text along the cause chain, without the driver's own top message (review M2: it
    * echoes the statement, `Error while executing SQL "..."`, which would satisfy any text check).
    */
  private def chain(e: Throwable): String =
    val all = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(12).toList
    (if all.size > 1 then all.tail else all)
      .map(t => s"${t.getClass.getSimpleName}: ${t.getMessage}")
      .mkString(" | ")

  /** `sql` through the Flight SQL edge as `u`: the rows (each column as text), or the failure. */
  private def flight(u: User, sql: String): Either[Failure, List[List[String]]] =
    val st = connection(u).createStatement()
    try
      if st.execute(sql) then
        val rs   = st.getResultSet
        val cols = rs.getMetaData.getColumnCount
        val out  = mutable.ListBuffer.empty[List[String]]
        while rs.next() do out += (1 to cols).map(i => String.valueOf(rs.getObject(i))).toList
        Right(out.toList)
      else Right(Nil)
    catch case e: SQLException => Left(Failure(statusOf(e), chain(e)))
    finally st.close()

  /** A front door step: a statement relayed through `quack_query_by_name`, or a line the CLI runs
    * itself (its own BEGIN, ROLLBACK).
    */
  sealed trait Step
  final case class Remote(sql: String) extends Step
  final case class Local(line: String) extends Step

  /** `steps` through the native front door on ONE session: one DuckDB 2.0 CLI process ATTACHes the
    * door as `q` (TYPE quack), then runs each step, its rows into a file of its own and a marker on
    * stderr after it (stderr is unbuffered, so a step's error precedes its marker). Each step's
    * outcome: its rows (Right), or what the CLI printed on stderr for it (Left). The token rides
    * the CLI's stdin, never its command line.
    */
  private def door(u: User, steps: List[Step]): List[Either[String, List[List[String]]]] =
    assume(AuthzStack.cliPresent, "no DuckDB 2.0 CLI on PATH")
    val dir   = Files.createTempDirectory("qod-search-e2e-door").toRealPath()
    val token = s"tenant=$Tenant&pool=bi&user=${u.login}&password=${password(u)}" +
      (if u == User.Admin then "&superuser=true" else "")
    def marker(i: Int) = s"qod-step-$i-end"
    Files.writeString(dir.resolve("mark.sh"), "echo \"qod-step-$1-end\" 1>&2\n")
    val script = new StringBuilder
    script.append(
      s"ATTACH 'quack:127.0.0.1:${stack.quackPort}' AS q (TYPE quack, TOKEN ${AuthzStack.lit(token)});\n"
    )
    steps.zipWithIndex.foreach { (step, i) =>
      script.append(s".output ${dir.resolve(s"out-$i")}\n")
      step match
        case Remote(sql) =>
          script.append(s"SELECT * FROM quack_query_by_name('q', ${AuthzStack.lit(sql)});\n")
        case Local(line) => script.append(s"$line\n")
      script.append(".output\n")
      script.append(s".shell /bin/sh ${dir.resolve("mark.sh")} $i\n")
    }
    val err = new StringBuffer
    try
      val rc = (Process(Seq("duckdb", "-no-agent", "-csv", "-noheader")) #<
        new ByteArrayInputStream(script.toString.getBytes(StandardCharsets.UTF_8)))
        .!(ProcessLogger(_ => (), l => err.append(l).append('\n')))
      val text = err.toString
      withClue(s"the CLI exited $rc: $text") {
        steps.indices.foreach(i => text should include(marker(i)))
      }
      steps.indices.toList.map { i =>
        val from    = if i == 0 then 0 else text.indexOf(marker(i - 1)) + marker(i - 1).length
        val stepErr = text.substring(from, text.indexOf(marker(i))).trim
        if stepErr.nonEmpty then Left(stepErr)
        else
          val out = dir.resolve(s"out-$i")
          Right(
            (if Files.exists(out) then Files.readAllLines(out).asScala.toList else Nil)
              .map(_.trim)
              .filter(_.nonEmpty)
              .map(_.split(",", -1).toList)
          )
      }
    finally
      Try {
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  private def doorOne(u: User, sql: String): Either[String, List[List[String]]] =
    door(u, List(Remote(sql))).head

  // ---------- expectations ----------

  /** `rows` against `expect` (an answer, not a refusal). */
  private def checkRows(expect: Expect, rows: List[List[String]]): Unit = expect match
    case Expect.Ranked(scored, k, title, differs) =>
      val top = Bm25.top(scored, k)
      withClue(s"reference top ${top.map((key, r) => s"$key:${r.score}")}; answer $rows") {
        rows.size shouldBe top.size
        val hits = rows.map { r =>
          (
            r.head.toLong,
            if title.isDefined then Some(r(1)) else None,
            r(r.size - 2).toDouble,
            r.last.toInt
          )
        }
        hits.map(_._4) shouldBe (1 to hits.size).toList
        // Position by position, the scores of the reference's top k (invariant 7).
        hits.zip(top).foreach { case ((_, _, score, _), (_, ref)) =>
          math.abs(score - ref.score) should be <= ref.tolerance
        }
        hits.map(_._1).distinct.size shouldBe hits.size
        val cutoff = top.lastOption.map(_._2)
        hits.foreach { (key, t, score, _) =>
          val ref = scored.getOrElse(key, fail(s"$key is no match of the population"))
          math.abs(score - ref.score) should be <= ref.tolerance
          cutoff.foreach(c => ref.score should be >= (c.score - c.tolerance - ref.tolerance))
          title.foreach(expected => t shouldBe Some(expected(key)))
          differs.foreach { other =>
            other.get(key).foreach(o => math.abs(score - o.score) should be > o.tolerance)
          }
        }
        // Ties are pooled at the accuracy bound: every match clearly above the k-th is returned.
        cutoff.foreach { c =>
          scored
            .filter((_, r) => r.score > c.score + c.tolerance + r.tolerance)
            .keys
            .foreach(key => hits.map(_._1) should contain(key))
        }
      }
    case Expect.Facets(counts) =>
      rows.map(r => r.head -> r(1).toLong).toMap shouldBe counts
      rows.map(_.head) shouldBe rows.map(_.head).sorted
    case Expect.NoRows     => rows shouldBe empty
    case r: Expect.Refused => fail(s"expected a refusal $r, got rows $rows")

  private def checkFlight(expect: Expect, outcome: Either[Failure, List[List[String]]]): Unit =
    (expect, outcome) match
      case (r: Expect.Refused, Left(f)) =>
        withClue(f.message) {
          f.code shouldBe Some(r.code)
          r.has.foreach(h => f.message should include(h))
          r.hasNot.foreach(h => f.message should not include h)
        }
      case (r: Expect.Refused, Right(rows)) => fail(s"expected $r, got rows $rows")
      case (_, Left(f))                     => fail(s"refused: ${f.code} ${f.message}")
      case (e, Right(rows))                 => checkRows(e, rows)

  private def checkDoor(expect: Expect, outcome: Either[String, List[List[String]]]): Unit =
    (expect, outcome) match
      case (r: Expect.Refused, Left(raw)) =>
        // The DuckDB client echoes the statement's start after its error (`LINE 1: ...`): only the
        // error itself is judged.
        val err = raw.indexOf("LINE 1:") match
          case -1 => raw
          case at => raw.substring(0, at)
        withClue(raw) {
          r.has.foreach(h => err should include(h))
          r.hasNot.foreach(h => err should not include h)
        }
      case (r: Expect.Refused, Right(rows)) => fail(s"expected $r, got rows $rows")
      case (_, Left(err))                   => fail(s"refused: $err")
      case (e, Right(rows))                 => checkRows(e, rows)

  // ---------- the superuser's decide counter (Task 7) ----------

  /** The leader's export of every node's `qod_authz_stats().decide_calls` for this tenant. */
  private def decideCalls(): Option[Double] =
    val (code, body) = stack.getStatus("/metrics", "none")
    Option
      .when(code == 200) {
        body.linesIterator
          .filter(l => l.startsWith("qod_node_authz_decide_calls_total{") && l.contains(Tenant))
          .map(l => l.substring(l.lastIndexOf(' ') + 1).toDouble)
          .toList
      }
      .filter(_.nonEmpty)
      .map(_.sum)

  /** The counter once it holds still over three probe ticks (healthCheckIntervalSec = 2). */
  private def settledDecideCalls(): Double =
    stack.await("the node's decide_calls settled", 90.seconds) {
      val a = decideCalls()
      Thread.sleep(6000)
      val b = decideCalls()
      a.filter(x => b.contains(x))
    }

  "a superuser's search" should "make no decide call on the node (Task 7)" in {
    ready()
    assume(AuthzStack.cliPresent, "no DuckDB 2.0 CLI on PATH")
    val before = settledDecideCalls()
    val relay  = stack.relay.decideCalls
    val expect = Expect.Ranked(
      world.byId(User.Admin, world.pagesNow, Q1),
      10,
      Some(world.titleFor(User.Admin))
    )
    checkFlight(expect, flight(User.Admin, topSql(world.pages, Q1)))
    checkDoor(expect, doorOne(User.Admin, topSql(world.pages, Q1)))
    Thread.sleep(6000)
    info(
      s"decide_calls before $before, after ${decideCalls()}; relayed ${stack.relay.decideCalls - relay}"
    )
    decideCalls() shouldBe Some(before)
    stack.relay.decideCalls shouldBe relay
    // The counter is live: a tenant user's statement of a shape no node has a verdict for moves it.
    val control =
      s"SELECT max(h.rank) FROM qod_search((SELECT rowid FROM ${world.pages}), '${world.pages}', " +
        s"['body'], '$Q1') h"
    flight(User.Plain, control).isRight shouldBe true
    stack.await("the control's decide call exported", 60.seconds)(
      decideCalls().filter(_ > before)
    )
    stack.relay.decideCalls should be > relay
  }

  // ---------- the matrix ----------

  matrix.foreach { c =>
    c.edges.foreach { edge =>
      s"${c.user.login} on $edge" should c.name in {
        ready()
        val sql = c.sql(world)
        val exp = c.expect(world)
        edge match
          case Edge.Flight =>
            val out = flight(c.user, sql)
            info(
              out.fold(f => s"refused ${f.code}: ${f.message.take(400)}", r => s"${r.size} rows")
            )
            checkFlight(exp, out)
          case Edge.Quack =>
            val out = doorOne(c.user, sql)
            info(out.fold(e => s"refused: ${e.take(400)}", r => s"${r.size} rows"))
            checkDoor(exp, out)
      }
    }
  }

  // ---------- quack only: an interactive transaction on pages_rw (Task 14) ----------

  /** The search of `pages_rw` for `token`, with its join-back. */
  private def rwSearch(token: String): String =
    s"SELECT p.id, h.score, h.rank FROM qod_search((SELECT rowid FROM ${world.rw}), '${world.rw}', " +
      s"['body'], '$token') h JOIN ${world.rw} p ON p.rowid = h.rowid ORDER BY h.rank"

  private def rwInsert(id: Int, token: String): String =
    s"INSERT INTO ${world.rw} VALUES ($id, 't$id', '$token lake', 0, 'alpha')"

  /** The client's first read of the attached catalog inside its BEGIN: the DuckDB client then opens
    * its remote transaction (`BEGIN TRANSACTION`), and the front door holds that transaction's node
    * link, on which every later statement of the session runs until COMMIT or ROLLBACK.
    */
  private def touch: Step = Local("SELECT count(*) FROM q.main.pages_rw;")

  /** `pages_rw`'s live rows now. */
  private def rwNow(): List[Doc] = docs(world.rw)

  List(User.Plain -> 9101, User.Rls10 -> 9102).foreach { (u, id) =>
    s"${u.login} on Quack" should "see its own insert inside a transaction, and not after ROLLBACK" in {
      ready()
      val token  = s"zebratx${u.login}"
      val newDoc = Doc(-1, id, s"t$id", s"$token lake", 0, "alpha")
      // The transaction's own row joins the population it searches (read-your-writes, Task 14).
      val pop = world.population(u, rwNow()) :+ newDoc
      val ref = Bm25.scores(pop, List(newDoc), token)(-1)
      val out = door(
        u,
        List(
          Local("BEGIN;"),
          touch,
          Remote(rwInsert(id, token)),
          Remote(rwSearch(token)),
          Local("ROLLBACK;"),
          Remote(rwSearch(token))
        )
      )
      info(out.mkString("\n"))
      out(0) shouldBe Right(Nil)
      out(1).isRight shouldBe true
      out(2).isRight shouldBe true
      out(3) match
        case Right(List(List(gotId, score, rank))) =>
          gotId shouldBe id.toString
          rank shouldBe "1"
          math.abs(score.toDouble - ref.score) should be <= ref.tolerance
        case other => fail(s"the search inside the transaction answered $other")
      out(4) shouldBe Right(Nil)
      out(5) shouldBe Right(Nil)
    }

    it should "read the transaction-abort answer for a search over the caps, then ROLLBACK" in {
      ready()
      val token = s"zebracap${u.login}"
      val out   = door(
        u,
        List(
          Local("BEGIN;"),
          touch,
          Remote(rwInsert(id + 10, token)),
          Remote(bareSql(world.behind, Q1)),
          Remote(rwSearch(token)),
          Local("ROLLBACK;"),
          Remote(rwSearch(token))
        )
      )
      info(out.mkString("\n"))
      out(2).isRight shouldBe true
      out(3) match
        case Left(err) => err should include(world.behindText(u))
        case other     => fail(s"the search over the caps answered $other")
      // The transaction is over: nothing but ROLLBACK runs in it. What the client reads here is its
      // own aborted-transaction message (the CLI aborts its local transaction after the failure and
      // sends nothing), not the front door's fence, which this spec cannot see (review M6).
      out(4) match
        case Left(err) => err should include("transaction is aborted")
        case other     => fail(s"a statement after the failure answered $other")
      out(5) shouldBe Right(Nil)
      // Rolled back: the insert is gone.
      out(6) shouldBe Right(Nil)
    }
  }

  // ---------- flight only: one request holding BEGIN ... COMMIT ----------

  /** `BEGIN; INSERT; search; COMMIT` as one request (each FlightSQL request runs on a node
    * connection of its own, so a transaction lives within one request).
    */
  private def batch(id: Int, token: String): String =
    s"BEGIN; ${rwInsert(id, token)}; SELECT p.id FROM qod_search((SELECT rowid FROM ${world.rw}), " +
      s"'${world.rw}', ['body'], '$token') h JOIN ${world.rw} p ON p.rowid = h.rowid; COMMIT"

  // Lead ruling C (Task 18 fix round 1): a submission answers with its last statement's result,
  // here COMMIT's, so the batch is asserted to succeed and a new request to find the committed row.
  // Read-your-writes inside a transaction stays covered by the front door rows and the node tests.
  List(User.Plain -> 9201, User.Admin -> 9202).foreach { (u, id) =>
    s"${u.login} on Flight" should "run BEGIN; INSERT; search; COMMIT, the row found after" in {
      ready()
      val token = s"zebra${u.login}"
      val out   = flight(u, batch(id, token))
      info(out.toString)
      out.isRight shouldBe true
      // Committed: a new request finds it, alone, with the score of the population it joined.
      val newDoc = Doc(-1, id, s"t$id", s"$token lake", 0, "alpha")
      val pop    = world.population(u, rwNow().filterNot(_.id == id)) :+ newDoc
      val ref    = Bm25.scores(pop, List(newDoc), token)(-1)
      flight(u, rwSearch(token)) match
        case Right(List(List(gotId, score, rank))) =>
          gotId shouldBe id.toString
          rank shouldBe "1"
          math.abs(score.toDouble - ref.score) should be <= ref.tolerance
        case other => fail(s"the search after the batch answered $other")
    }
  }

  /** A denial of `ProtectedWriteGuard` (review I2), its own text logged first (review I1). */
  private def guardDenied(text: String) = gatewayDenied(text)

  private def logged(out: Either[Failure, List[List[String]]]) =
    info(out.fold(f => s"refused ${f.code}: ${f.message.take(500)}", r => s"rows $r"))
    out

  /** The guard's own texts, as fix round 2's first run logged them. A policy holder's `BEGIN` batch
    * is denied on the guard's fail-closed path at `BEGIN` (its ACL parse reads no `BEGIN`, so the
    * guard denies at the first statement it cannot analyse, before judging any read, whatever the
    * batch reads; review I2); the batch with no `BEGIN` below exercises its read judgement. A write
    * reading a policy table is denied on its policy path.
    */
  private val GuardBatchText =
    "statement could not be fully analysed and reads a protected schema"
  private val GuardBatchReadText = "cannot be read in a multi-statement batch"
  private val GuardWriteText     =
    "pages is protected by a column/row policy and cannot be read into a write statement"

  List(User.Rls10 -> 9203, User.ClsMask -> 9204).foreach { (u, id) =>
    s"${u.login} on Flight" should "be denied BEGIN; INSERT; search; COMMIT by the guard" in {
      ready()
      checkFlight(guardDenied(GuardBatchText), logged(flight(u, batch(id, s"zebra${u.login}"))))
      flight(u, rwSearch(s"zebra${u.login}")) match
        case Right(rows) => rows shouldBe empty
        case Left(f)     => info(s"the check search refused: ${f.message.take(200)}")
    }

    it should "have a batch with no BEGIN judged on what it reads by the guard" in {
      ready()
      val token = s"zebranb${u.login}"
      val out   = logged(
        flight(
          u,
          s"${rwInsert(id + 2, token)}; SELECT p.id, h.score FROM qod_search((SELECT rowid FROM " +
            s"${world.rw}), '${world.rw}', ['body'], '$token') h JOIN ${world.rw} p " +
            "ON p.rowid = h.rowid"
        )
      )
      // A policy table read in a multi-statement batch (review I2's batchDeny): for the row-policy
      // holder, and for the mask holder too, though it reads only p.id of pages_rw (the fix round 3
      // probe logged the guard's denial before this was asserted).
      checkFlight(gatewayDenied(GuardBatchReadText), out)
      out.left.foreach(f => f.message should include("pages_rw is protected"))
    }

    it should "be denied INSERT INTO ... SELECT ... FROM qod_search(...) by the guard" in {
      ready()
      checkFlight(
        guardDenied(GuardWriteText),
        logged(
          flight(
            u,
            s"INSERT INTO ${world.rw} (id, title, body, f10, cat) SELECT p.id + 10000, p.title, " +
              s"p.body, p.f10, p.cat FROM qod_search((SELECT rowid FROM ${world.pages}), " +
              s"'${world.pages}', ['body'], '$Q1') h JOIN ${world.pages} p ON p.rowid = h.rowid"
          )
        )
      )
    }

    it should "be denied CREATE TABLE AS of a search by the guard" in {
      ready()
      checkFlight(
        guardDenied(GuardWriteText),
        logged(
          flight(
            u,
            s"CREATE TABLE $lake.main.x_${u.login} AS SELECT h.rowid, h.score FROM " +
              s"qod_search((SELECT rowid FROM ${world.pages}), '${world.pages}', ['body'], '$Q1') h"
          )
        )
      )
    }
  }
