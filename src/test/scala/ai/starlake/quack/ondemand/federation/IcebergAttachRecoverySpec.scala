package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.ondemand.federation.iceberg.{IcebergAuthType, IcebergRestConfig}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the two engine behaviours `IcebergAttachVerifier` is built on, against a real duckdb CLI
  * driven the way a node is driven: SQL on stdin, one statement per line, as
  * `scripts/spawn-quack-node.sh` feeds it through a held-open FIFO.
  *
  *   1. A failed ATTACH does NOT abort the statements after it. This is why a node comes up healthy
  *      with a catalog silently missing, and therefore why the verifier has to exist at all. If a
  *      DuckDB bump ever makes the CLI bail instead, this spec fails and the verifier's whole
  *      rationale needs revisiting.
  *   2. Re-issuing the rendered block on a live session attaches the catalog, which is what makes
  *      the verifier's self-healing step work without a node restart.
  *
  * The driver shape is part of the test, not an implementation detail of it. Probed at DuckDB
  * v1.5.4, `duckdb -c` aborts at the first failing statement, and so does any single input line
  * carrying several statements; only newline-separated statements on stdin keep going. Run any
  * other way, behaviour (1) reads as false. See `IcebergFixture`'s scaladoc for both probes.
  *
  * Exit code is non-zero in both continuing cases, because a statement did fail. That code is
  * exactly the signal a node discards: `spawn-quack-node.sh` leaves duckdb serving on the FIFO and
  * never inspects it, so the process reports failure while the pool reports healthy. The assertions
  * below pin it as non-zero for that reason, and a spec expecting 0 here would be asserting the
  * opposite of what the node actually sees.
  *
  * Case 1 needs only a duckdb CLI and an endpoint that refuses connections; case 2 also needs the
  * live fixture. Cancelled, not failed, when what it needs is absent.
  */
class IcebergAttachRecoverySpec extends AnyFlatSpec with Matchers:

  /** Port 9 is `discard`, reserved and unbound here, so the ATTACH fails on connect rather than on
    * a protocol error. Nothing in this spec depends on which of the two it is.
    */
  private val deadConfig = IcebergRestConfig(
    uri = "http://127.0.0.1:9",
    warehouse = "wh",
    authType = Some(IcebergAuthType.NoAuth)
  )

  private val liveConfig = IcebergRestConfig(
    uri = IcebergFixture.endpoint,
    warehouse = IcebergFixture.warehouse,
    authType = Some(IcebergAuthType.NoAuth)
  )

  private def deadAttach(alias: String): String =
    IcebergFixture.attachBlock(deadConfig, alias, readOnly = false)

  private def attached(alias: String, label: String): String =
    s"SELECT '$label=' || count(*) FROM duckdb_databases() WHERE database_name = '$alias';"

  "a failed ATTACH" should "not abort the statements after it" in {
    assume(IcebergFixture.duckdbPresent, IcebergFixture.missingDuckdb)
    val r = IcebergFixture.duckdb(
      IcebergFixture.script(
        deadAttach("dead"),
        "SELECT 'after=' || 42;",
        attached("dead", "attached")
      )
    )
    withClue(r.clue) {
      // The ATTACH really failed. Without this the rest would pass just as well on a run where it
      // had succeeded, which is the arm-attribution trap: 'after=42' alone attributes nothing.
      r.errors.mkString("\n") should include("127.0.0.1:9")
      // And execution continued past it, leaving the catalog absent rather than half-attached.
      r.values shouldBe List("after=42", "attached=0")
      r.code should not be 0
    }
  }

  "re-issuing the rendered block" should "attach the catalog on a live session" in {
    assume(IcebergFixture.duckdbPresent, IcebergFixture.missingDuckdb)
    assume(IcebergFixture.reachable, IcebergFixture.missingFixture)
    val r = IcebergFixture.duckdb(
      IcebergFixture.script(
        deadAttach("lake"),
        attached("lake", "first"),
        IcebergFixture.storagePrelude,
        IcebergFixture.attachBlock(liveConfig, "lake", readOnly = false),
        attached("lake", "second")
      )
    )
    withClue(r.clue) {
      // first=0 is what makes second=1 a recovery rather than a single successful attach: a run
      // where the dead ATTACH had somehow worked would report first=1 and prove nothing. The
      // `true` the CREATE SECRET reports is dropped by DuckdbRun.values.
      r.values shouldBe List("first=0", "second=1")
      r.code should not be 0
    }
  }
