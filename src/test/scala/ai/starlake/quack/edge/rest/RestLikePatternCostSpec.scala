package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection

/** How expensive is the worst LIKE pattern the caps allow (at most 4 `*` wildcards, values up to 4
  * KiB), measured on the pinned DuckDB? LIKE is core, so in-process DuckDB (the JDBC driver pinned
  * in `Versions.duckdb`) measures the engine the nodes run, without a node.
  *
  * The worst subject is a value made of the repeated letter the pattern's segments match, followed
  * by a final segment that never does, so a backtracking matcher explores every placement before
  * failing.
  *
  * Measured on DuckDB 1.5 (4 cores, CLI 1.5.4 and JDBC 1.5.5.1), per ROW of the given length:
  *   - plain LIKE (only `%` and literal text): linear, about 1 ms at 4 KiB even with 4 wildcards
  *     and 1000-character segments (DuckDB's segment matcher);
  *   - ILIKE, and any LIKE with `ESCAPE` or `_`: DuckDB's backtracking matcher, cost length^(inner
  *     wildcards). `%a%a%b%` under ILIKE: 0.63 s at 1 KiB, 35 s at 4 KiB. With ESCAPE: one wildcard
  *     11-21 ms (length x needle), two short-segment wildcards 30 ms, two wildcards with
  *     1000-character segments 3.9 s, three 42 s, all at 4 KiB. So "at most 2 wildcards with
  *     ESCAPE" would still admit seconds per row;
  *   - `lower(col) LIKE lower(pattern)`, `=`, `starts_with`, `ends_with`, `contains`: about 1 ms at
  *     4 KiB, needles of 2000 to 4000 characters included.
  *
  * DuckDB 2.0 (JDBC 2.0.0.0, the pinned engine, 4 cores) no longer backtracks: `%a%a%b%` costs
  * about 0.7 ms at 4 KiB under LIKE, ILIKE, `_` and ESCAPE alike, and the escaped forms with one to
  * three 1000-character segments 2.5 to 8 ms.
  *
  * Hence the edge's rule: `ilike` renders as `lower(col) LIKE lower(pattern)`, a pattern holding a
  * literal `%` or `_` is an equality, prefix, suffix or substring test (`*` only at its ends), and
  * `ESCAPE` is never emitted ([[PatternForm]]). The first test keeps the engine characterization
  * (small subjects, so it stays cheap); the others pin the cost of the worst ADMITTED patterns and
  * the refusal of the shapes that left the linear path on 1.5. The rule is kept on 2.0: it costs
  * nothing, and it keeps the edge's worst case independent of the engine's matcher.
  */
class RestLikePatternCostSpec extends AnyFlatSpec with Matchers:

  import ai.starlake.quack.edge.policy.PolicyPipelineFixture.*

  /** Best of `n` runs, in milliseconds: the least noisy estimate of the engine's own cost. */
  private def bestMillis(conn: Connection, sql: String, n: Int = 3): Double =
    (1 to n).map { _ =>
      val t0 = System.nanoTime()
      query(conn, sql)
      (System.nanoTime() - t0) / 1e6
    }.min

  private def subject(conn: Connection, bytes: Int): String =
    val t = s"subject_$bytes"
    exec(conn, s"CREATE TABLE $t AS SELECT repeat('a', $bytes) AS s")
    t

  /** The edge's own statement for one filter on column `s`, through parse, resolve and render. */
  private def rendered(table: String, filter: String): Either[String, String] =
    RestQuery
      .parse(Seq("select" -> "s", "s" -> filter))
      .flatMap(RestResolver.resolve(_, Vector(ProbedColumn("s", ColumnKind.Text))))
      .map(RestSql.render(RestSql.Target("memory", "main", table, None), _, 100))
      .left
      .map(_.code)

  "the worst four-wildcard pattern" should "be linear in every form on the pinned DuckDB, ILIKE and ESCAPE included (measured, see class comment)" in
    withDuckDb("memory") { conn =>
      val small                          = subject(conn, 512)
      val large                          = subject(conn, 4096)
      def count(t: String, pred: String) = s"SELECT count(*) FROM $t WHERE s $pred"
      val forms                          = List(
        "like"        -> "LIKE '%a%a%b%'",
        "ilike"       -> "ILIKE '%a%a%b%'",
        "like+escape" -> """LIKE '%a%a%b\%%' ESCAPE '\'""",
        "like+_"      -> "LIKE '%a%a%b_'"
      )

      for (name, pred) <- forms; t <- List(small, large) do
        query(conn, count(t, pred)) shouldBe List(List("0"))
        val ms = bestMillis(conn, count(t, pred))
        info(f"$name%-12s $t%-13s $ms%9.1f ms")
        // Measured under 1 ms at 4 KiB; DuckDB 1.5's backtracking matcher took 35 s for ILIKE.
        // 250 ms is a generous margin for a loaded machine that still fails if a form backtracks.
        withClue(s"$name on $t")(ms should be < 250.0)
    }

  it should "stay on the fast path when rendered as lower(col) LIKE lower(pattern)" in
    withDuckDb("memory") { conn =>
      val t  = subject(conn, 4096)
      val ms = bestMillis(conn, s"SELECT count(*) FROM $t WHERE lower(s) LIKE lower('%a%a%b%')")
      info(f"lower-like   $t%-13s $ms%9.1f ms")
      ms should be < 1000.0
    }

  // The worst patterns the grammar admits against a 4 KiB value, rendered by the edge itself: four
  // wildcards with the longest segments the value cap allows, like and ilike, and every needle
  // form with a needle the size of the value. Each measured at about 1 ms (see class comment).
  private val a1000         = "a" * 1000
  private val WorstAdmitted = List(
    "like four long segments"  -> s"like.*$a1000*$a1000*${a1000}b*",
    "ilike four long segments" -> s"ilike.*$a1000*$a1000*${a1000}b*",
    "like short segments"      -> "like.*a*a*b*",
    "ilike short segments"     -> "ilike.*a*a*b*",
    "contains, literal %"      -> s"ilike.*${"a" * 2000}b%*",
    "ends_with, literal _"     -> s"like.*${"a" * 2000}b_",
    "starts_with, literal %"   -> s"ilike.${"a" * 4000}%*",
    "equality, literal _"      -> s"ilike.${"a" * 4000}_"
  )

  "the worst admitted pattern" should "cost a few milliseconds on a 4 KiB value" in
    withDuckDb("memory") { conn =>
      val t = subject(conn, 4096)
      WorstAdmitted.foreach { (name, filter) =>
        val sql = rendered(t, filter).fold(code => fail(s"$name refused: $code"), identity)
        sql should not include "ILIKE"
        sql should not include "ESCAPE"
        query(conn, sql) shouldBe Nil
        val ms = bestMillis(conn, sql)
        info(f"$name%-26s $ms%9.1f ms")
        // Measured about 1 ms; the refused shapes cost 30 ms to 42 s. 250 ms is a generous
        // margin for a loaded machine that still fails if a form leaves the linear path.
        withClue(name)(ms should be < 250.0)
      }
    }

  "a pattern that would leave the linear path" should "be refused" in
    // An inner wildcard beside a literal % or _ needs ESCAPE, whatever the case mode.
    List(
      "like.*a*a*b%",
      "ilike.*a*a*b%",
      s"like.*$a1000*${a1000}b_",
      "ilike.a*_",
      "like._*a"
    ).foreach { filter =>
      withClue(filter)(rendered("t", filter) shouldBe Left("invalid_filter"))
    }
