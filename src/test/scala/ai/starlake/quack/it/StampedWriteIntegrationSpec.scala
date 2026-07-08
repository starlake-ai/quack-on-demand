package ai.starlake.quack.it

import ai.starlake.quack.edge.adapter.{QuackHttpClient, QuackResponse}
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.net.{HttpURLConnection, URI}
import java.nio.file.{Files, Path}
import scala.sys.process._

/** EPIC P1 end-to-end: the stamped-write bracket against a REAL duckdb + quack + ducklake node.
  * Spawns its own server on a scratch port; skips (assume) when the duckdb CLI is absent.
  */
class StampedWriteIntegrationSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:

  private val port                   = 29993
  private val endpoint               = s"quack:localhost:$port"
  private val token                  = "it-stamp-token"
  private var dir: Path              = null
  private var server: Process        = null
  private val duckdbPresent: Boolean = Process("which duckdb").!(ProcessLogger(_ => ())) == 0

  override def beforeAll(): Unit =
    if duckdbPresent then
      dir = Files.createTempDirectory("qod-stamp-it")
      val init =
        s"""INSTALL quack; LOAD quack;
           |INSTALL ducklake; LOAD ducklake;
           |ATTACH 'ducklake:$dir/meta.ducklake' AS lake (DATA_PATH '$dir/data');
           |USE lake;
           |CREATE TABLE t(i INT);
           |CALL quack_serve('quack:0.0.0.0:$port', token := '$token', allow_other_hostname := true);
           |.shell sleep 600
           |""".stripMargin
      Files.writeString(dir.resolve("init.sql"), init)
      server =
        Process(Seq("duckdb", "-init", dir.resolve("init.sql").toString, "-batch"), dir.toFile).run(
          ProcessLogger(_ => (), _ => ())
        )
      // Poll until the HTTP endpoint answers (404 on GET is fine; it wants POST).
      // Use 127.0.0.1 directly to avoid the IPv6 probe that "localhost" triggers on macOS,
      // which eats the connect timeout before the IPv4 attempt lands.
      val deadline = System.currentTimeMillis() + 20000
      var up       = false
      while !up && System.currentTimeMillis() < deadline do
        up =
          try
            val c = URI
              .create(s"http://127.0.0.1:$port/quack")
              .toURL
              .openConnection()
              .asInstanceOf[HttpURLConnection]
            c.setConnectTimeout(300); c.setReadTimeout(300)
            c.getResponseCode; c.disconnect(); true
          catch { case _: Throwable => Thread.sleep(300); false }
      up shouldBe true

  override def afterAll(): Unit =
    if server != null then server.destroy()

  private def client() =
    new QuackHttpClient(new RootAllocator(), nativeClient = true, nodeDisableSsl = true)

  private def rows(resp: QuackResponse): List[List[Any]] =
    val ok   = resp.asInstanceOf[QuackResponse.Ok]
    val out  = scala.collection.mutable.ListBuffer.empty[List[Any]]
    val root = ok.rows.getVectorSchemaRoot
    while ok.rows.loadNextBatch() do
      (0 until root.getRowCount).foreach { i =>
        out += root.getFieldVectors.toArray.toList
          .map(_.asInstanceOf[org.apache.arrow.vector.FieldVector].getObject(i))
      }
    ok.close()
    out.toList

  private def latestSnapshot(c: QuackHttpClient): (Any, Any) =
    val r = rows(
      c.query(
        endpoint,
        token,
        "SELECT author, commit_message FROM ducklake_snapshots('lake') ORDER BY snapshot_id DESC LIMIT 1",
        None
      ).unsafeRunSync()
    )
    (r.head.head, r.head(1))

  private val prelude =
    "BEGIN; CALL ducklake_set_commit_message('lake', 'tenant:acme/user:alice', 'flightsql insert')"

  it("preserves the DML Count and stamps the snapshot"):
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val c = client()
    val r = rows(
      c.queryStamped(endpoint, token, prelude, "USE lake.main; INSERT INTO lake.t VALUES (1)")
        .unsafeRunSync()
    )
    r.head.head.toString shouldBe "1" // exact Count
    val (author, msg) = latestSnapshot(c)
    author.toString shouldBe "tenant:acme/user:alice"
    msg.toString shouldBe "flightsql insert"

  it("leaves author null on a plain unstamped write"):
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val c = client()
    rows(
      c.query(endpoint, token, "USE lake.main; INSERT INTO lake.t VALUES (2)", None).unsafeRunSync()
    )
    val (author, _) = latestSnapshot(c)
    author shouldBe (null: Any)

  it("fail-open: a broken prelude still lands the write, unstamped"):
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val c   = client()
    val bad = "BEGIN; CALL ducklake_set_commit_message('no_such_catalog', 'a', 'm')"
    val r   = rows(
      c.queryStamped(endpoint, token, bad, "USE lake.main; INSERT INTO lake.t VALUES (3)")
        .unsafeRunSync()
    )
    r.head.head.toString shouldBe "1"
    val (author, _) = latestSnapshot(c)
    author shouldBe (null: Any)

  it("commits even when the caller closes without draining"):
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val c    = client()
    val resp = c
      .queryStamped(endpoint, token, prelude, "USE lake.main; INSERT INTO lake.t VALUES (4)")
      .unsafeRunSync()
    resp.asInstanceOf[QuackResponse.Ok].close() // no drain
    val r = rows(
      c.query(endpoint, token, "SELECT count(*) FROM lake.t WHERE i = 4", None).unsafeRunSync()
    )
    r.head.head.toString shouldBe "1"
