package ai.starlake.quack.docs

import org.scalatest.funsuite.AnyFunSuite

class GenConfigDocsSpec extends AnyFunSuite:

  test("renders a grouped markdown table covering every registry entry") {
    val md = GenConfigDocs.render
    assert(md.startsWith("---"), "should emit Docusaurus front matter")
    assert(md.contains("QOD_ON_DEMAND_PORT"), "manager port env var missing")
    assert(md.contains("PROXY_PORT"), "flightsql port env var missing")
    assert(md.contains("QOD_ACL_MODE"), "acl mode env var missing")
    List("QOD_ACL_ENABLED", "QOD_ACL_FILTERED_METADATA", "QOD_ACL_DIALECT").foreach(v =>
      assert(!md.contains(v), s"the removed variable $v is still documented")
    )
    val entries = GenConfigDocs.entries
    assert(entries.nonEmpty)
    entries.foreach(e => assert(md.contains(e.envVar), s"missing row for ${e.envVar}"))
  }

  test("flags sensitive defaults") {
    val md = GenConfigDocs.render
    assert(md.toLowerCase.contains("yes"), "expected at least one sensitive=yes row")
  }

  test("groups flat top-level keys under a single section heading") {
    val md = GenConfigDocs.render
    assert(
      md.contains("## `quack-on-demand.admin`"),
      "nested admin block should be its own section"
    )
    assert(
      !md.contains("## `quack-on-demand.port`"),
      "flat scalar should not get its own per-key heading"
    )
  }

  /** The page's text outside inline code spans: what MDX parses as Markdown and JSX. */
  private def prose(md: String): String = md
    .split("`", -1)
    .zipWithIndex
    .collect {
      case (part, i) if i % 2 == 0 => part
    }
    .mkString(" ")

  test("MDX-escapes curly braces in descriptions so Docusaurus does not parse them as JSX") {
    val md = prose(GenConfigDocs.render)
    // Raw `{` and `}` would be parsed as JSX expression boundaries by MDX and
    // break the docs build. Every description that originally contained
    // braces must reach the page as HTML entities (inside a code span they are literal).
    assert(
      !md.contains("{"),
      "rendered page must not contain raw '{' outside code spans; descriptions must use &#123;"
    )
    assert(
      !md.contains("}"),
      "rendered page must not contain raw '}' outside code spans; descriptions must use &#125;"
    )
  }

  test("emits a description's code spans verbatim, never entity-escaped") {
    assert(
      GenConfigDocs.escapeText("on `http://<pod IP>:<port>/{x}` or {y} <z>") ==
        "on `http://<pod IP>:<port>/{x}` or &#123;y&#125; &lt;z>"
    )
    // A pipe still needs its table escape inside a code span; an unclosed backtick is prose.
    assert(GenConfigDocs.escapeText("`a|b` c|d") == "`a\\|b` c\\|d")
    assert(GenConfigDocs.escapeText("a `<b>") == "a `&lt;b>")
  }

  test("leaves no bare URL MDX would autolink outside a code span") {
    val out = GenConfigDocs.escapeText(
      "on http://<pod IP>:<manager port>, https://example.com/x or www.example.com"
    )
    List("http://", "https://", "www.", "<").foreach(t =>
      assert(!out.contains(t), s"'$t' reached the page as prose: $out")
    )
  }

  test("renders the placeholder URL examples as code spans, and no URL in prose") {
    val md = GenConfigDocs.render
    List(
      "`http://<QOD_POD_IP>:<manager port>`",
      "`http://<pod IP>:<manager port>`",
      "`http://host[:port]`"
    ).foreach { span =>
      val present = md.contains(span)
      assert(present, s"missing the code span $span")
    }
    val rows = md.linesIterator.filter(_.startsWith("| `")).mkString("\n")
    List("http://", "https://", "<").foreach { t =>
      val leaked = prose(rows).contains(t)
      assert(!leaked, s"'$t' reached a table row as prose")
    }
  }
