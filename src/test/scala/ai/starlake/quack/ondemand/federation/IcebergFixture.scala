package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.ondemand.federation.iceberg.{
  IcebergRestConfig,
  IcebergSetupSql,
  ValidatedIcebergConfig
}

import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** One duckdb CLI run: exit code plus each stream kept apart.
  *
  * The streams are NOT merged. Two threads drain them, so their relative interleaving is not
  * deterministic; a spec that asserted on a merged transcript would be asserting on thread
  * scheduling. Within one stream the order IS the order duckdb produced, which is what [[values]]
  * and [[errors]] let a spec pin.
  */
final case class DuckdbRun(code: Int, stdout: String, stderr: String):

  /** Result values, one per line, in `-noheader -list` mode.
    *
    * The `true` that `CREATE SECRET` reports is dropped: it belongs to
    * [[IcebergFixture.storagePrelude]], not to the statements a case is testing. No assertion in
    * these specs produces a bare `true` of its own, so nothing real is swallowed.
    */
  def values: List[String] = nonBlank(stdout).filterNot(_ == "true")

  def errors: List[String] = nonBlank(stderr)

  /** The transcript to show when an assertion fails, both streams labelled. */
  def clue: String = s"exit=$code\nstdout:\n$stdout\nstderr:\n$stderr"

  private def nonBlank(s: String): List[String] =
    s.linesIterator.map(_.trim).filter(_.nonEmpty).toList

/** Shared access to the `scripts/iceberg-fixture.yml` REST catalog and to a local duckdb CLI.
  *
  * Following the repo's convention for specs that need an external dependency
  * (`QuackCompatibilitySpec` and the `duckdb` binary), the specs using this CANCEL rather than fail
  * when the fixture or the CLI is absent, so a checkout without Docker still runs a green suite.
  * [[missingFixture]] and [[missingDuckdb]] carry the exact command that brings each one back.
  *
  * Two details of how SQL reaches duckdb are load bearing rather than style, because both specs
  * depend on the engine behaviour they select. Probed at DuckDB v1.5.4:
  *
  *   1. STDIN, not `duckdb -c`. `duckdb -c "<sql>"` ABORTS at the first failing statement: a failed
  *      Iceberg ATTACH followed by `SELECT 42` prints only the ATTACH error and exits 1, and the 42
  *      never runs. Over stdin the same input reports the error and CARRIES ON, so the 42 does run,
  *      while the process still exits non-zero because something failed.
  *   2. ONE STATEMENT PER LINE, which is what [[script]] builds. The CLI submits a whole input line
  *      as a single batch, so several statements sharing a line die together at the first error.
  *      Newline-separated, each one is submitted on its own and a failure only costs that one.
  *
  * Both match how a real node is driven: `scripts/spawn-quack-node.sh` holds a FIFO open on
  * duckdb's stdin and writes the assembled init SQL into it, accumulating it one statement per line
  * (`INIT_SQL+="..."$'\n'`). That combination is precisely why a node can come up healthy with one
  * catalog silently unattached, and therefore why `IcebergAttachVerifier` exists. Driving these
  * specs any other way would model an engine the node does not have.
  *
  * Output comes back in `-noheader -list` mode so a result is a bare value on its own line and a
  * spec can assert the producer's exact output instead of fishing for a substring inside ASCII box
  * drawing.
  */
object IcebergFixture:

  val endpoint: String  = sys.env.getOrElse("QOD_TEST_ICEBERG_ENDPOINT", "http://localhost:8181")
  val warehouse: String = sys.env.getOrElse("QOD_TEST_ICEBERG_WAREHOUSE", "warehouse")

  val s3Endpoint: String = sys.env.getOrElse("QOD_TEST_S3_ENDPOINT", "localhost:9000")

  private val duckdbBin: String = sys.env.getOrElse("DUCKDB_BIN", "duckdb")

  lazy val duckdbPresent: Boolean =
    Try(Process(Seq(duckdbBin, "-noheader", "-list", "-c", "SELECT 1;")).!(silent) == 0)
      .getOrElse(false)

  lazy val reachable: Boolean =
    Try {
      val url  = new java.net.URI(s"$endpoint/v1/config?warehouse=$warehouse").toURL
      val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setConnectTimeout(2000)
      conn.setReadTimeout(2000)
      try conn.getResponseCode < 500
      finally conn.disconnect()
    }.getOrElse(false)

  val missingFixture: String =
    s"Iceberg REST fixture not reachable at $endpoint. Start it with: " +
      "docker compose -f scripts/iceberg-fixture.yml up -d --wait"

  val missingDuckdb: String =
    s"duckdb CLI '$duckdbBin' not runnable. Install it (brew install duckdb, or see " +
      "https://duckdb.org/docs/installation) or point DUCKDB_BIN at it."

  /** The S3 secret the fixture needs, because it does not vend credentials. A catalog that DOES
    * vend (Polaris, S3 Tables) would need no `s3fix`, which is one reason this lives here rather
    * than in the renderer: QoD's rendered block is the ATTACH, not the storage credential.
    */
  val storagePrelude: String =
    "INSTALL httpfs;\nLOAD httpfs;\n" +
      "CREATE OR REPLACE SECRET s3fix (TYPE s3, KEY_ID 'admin', SECRET 'password', " +
      s"REGION 'us-east-1', ENDPOINT '$s3Endpoint', URL_STYLE 'path', USE_SSL false);\n"

  /** `IcebergSetupSql.render` accepts only a [[ValidatedIcebergConfig]], so every spec needs this
    * step. A validation failure in a fixture is a broken test, not a condition to route around, so
    * it throws with every error rather than yielding something degraded that a later assertion
    * would then blame on the catalog.
    */
  def validated(cfg: IcebergRestConfig, alias: String): ValidatedIcebergConfig =
    IcebergRestConfig.validated(cfg, alias) match
      case Right(v)   => v
      case Left(errs) =>
        throw new IllegalArgumentException(
          s"fixture config for alias '$alias' does not validate: ${errs.mkString("; ")}"
        )

  /** The rendered block for one config. This is the REAL producer's output: no spec hand-copies an
    * ATTACH, so a renderer that stopped emitting a needed option takes the specs down rather than
    * leaving them agreeing with a stale copy of themselves.
    *
    * `render` already emits one statement per line, and [[script]] preserves that.
    */
  def attachBlock(cfg: IcebergRestConfig, alias: String, readOnly: Boolean): String =
    IcebergSetupSql.render(validated(cfg, alias), readOnly)

  /** Join statements one per line, which is the separation the CLI needs to keep running past a
    * failure (see this object's scaladoc). Blank entries are dropped so a caller can splice in an
    * optional block without minding trailing newlines.
    */
  def script(parts: String*): String =
    parts.flatMap(_.linesIterator).map(_.trim).filter(_.nonEmpty).mkString("\n") + "\n"

  /** Run SQL through the duckdb CLI's stdin. */
  def duckdb(sql: String): DuckdbRun =
    val out  = new StringBuilder
    val err  = new StringBuilder
    val log  = ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    val in   = new java.io.ByteArrayInputStream(sql.getBytes("UTF-8"))
    val code = (Process(Seq(duckdbBin, "-noheader", "-list")) #< in).!(log)
    DuckdbRun(code, out.toString, err.toString)

  private def silent: ProcessLogger = ProcessLogger(_ => (), _ => ())
