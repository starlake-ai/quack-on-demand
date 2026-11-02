package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.Connection
import scala.util.{Failure, Success, Try}

/** Does DuckLake accept `AT (VERSION => n)` on a VIEW, and if it does, does the view read at that
  * snapshot?
  *
  * Until this is established on a real DuckLake, the edge does not send the clause to a view: a
  * name that is or was a view is read at the current state with no `X-QoD-Snapshot` and no caching,
  * and `asOf*` on it answers 400 `invalid_selector` (`RestEdgeHandlersSpec` pins that). This spec
  * is the gate for lifting the restriction: it must pass, with the clause either honoured or
  * refused, on a machine that can load the extension before views get time travel.
  *
  * Needs the `ducklake` extension, which in-process DuckDB installs from the extension repository.
  * Cancelled where that repository is unreachable, in the style of DuckLakeInitializerRaceSpec. The
  * metadata catalog is a local DuckDB file, so neither Postgres nor a node is needed.
  *
  * The one outcome that must never pass silently is "accepted but ignored": a view that answers
  * `AT (VERSION => n)` with the CURRENT rows would make a snapshot header on a view a lie. A
  * refusal is recorded rather than failed: it keeps the edge's current answer for views correct.
  */
class RestViewTimeTravelSpec extends AnyFlatSpec with Matchers:

  import ai.starlake.quack.edge.policy.PolicyPipelineFixture.*

  private def extensionUnavailable(t: Throwable): Boolean =
    val msg = Option(t.getMessage).getOrElse("").toLowerCase
    msg.contains("failed to download extension") ||
    msg.contains("unable to connect to extension repository") ||
    msg.contains("could not establish connection") ||
    (msg.contains("extension") && msg.contains("not found"))

  private def loadDuckLakeOrCancel(conn: Connection): Unit =
    Try {
      exec(conn, "INSTALL ducklake")
      exec(conn, "LOAD ducklake")
    } match
      case Success(_)                            => ()
      case Failure(t) if extensionUnavailable(t) =>
        cancel(s"DuckDB ducklake extension unavailable (${t.getMessage}); skipping")
      case Failure(t) => throw t

  "AT (VERSION => n) on a DuckLake view" should "read the view at that snapshot, or be refused, but never be ignored" in
    withDuckDb("memory") { conn =>
      loadDuckLakeOrCancel(conn)
      val dir = Files.createTempDirectory("qod-view-at-")
      exec(
        conn,
        s"ATTACH 'ducklake:${dir.resolve("meta.ducklake")}' AS lake " +
          s"(DATA_PATH '${dir.resolve("data")}/')"
      )
      exec(conn, "CREATE TABLE lake.main.t (id INTEGER)")
      exec(conn, "INSERT INTO lake.main.t VALUES (1)")
      exec(conn, "CREATE VIEW lake.main.v AS SELECT id FROM lake.main.t")
      val snapshot =
        query(conn, "SELECT max(snapshot_id) FROM ducklake_snapshots('lake')").head.head
      exec(conn, "INSERT INTO lake.main.t VALUES (2), (3)")

      // Control: the table itself honours the clause.
      query(conn, s"SELECT count(*) FROM lake.main.t AT (VERSION => $snapshot)") shouldBe
        List(List("1"))

      Try(
        query(conn, s"""SELECT count(*) FROM "lake"."main"."v" AT (VERSION => $snapshot)""")
      ) match
        case Success(rows) =>
          info(s"AT accepted on a view at snapshot $snapshot -> $rows")
          rows shouldBe List(List("1")) // not 3: the current rows would mean the clause is ignored
        case Failure(t) =>
          info(s"AT refused on a view: ${t.getMessage}")
          succeed
    }
