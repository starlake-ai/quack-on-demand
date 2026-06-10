package ai.starlake.quack.docs

import org.scalatest.funsuite.AnyFunSuite

class GenConfigDocsSpec extends AnyFunSuite:

  test("renders a grouped markdown table covering every registry entry") {
    val md = GenConfigDocs.render
    assert(md.startsWith("---"), "should emit Docusaurus front matter")
    assert(md.contains("QOD_ON_DEMAND_PORT"), "manager port env var missing")
    assert(md.contains("PROXY_PORT"), "flightsql port env var missing")
    assert(md.contains("QOD_ACL_ENABLED"), "acl enable env var missing")
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
    assert(md.contains("## `quack-on-demand.admin`"), "nested admin block should be its own section")
    assert(!md.contains("## `quack-on-demand.port`"), "flat scalar should not get its own per-key heading")
  }

  test("MDX-escapes curly braces in descriptions so Docusaurus does not parse them as JSX") {
    val md = GenConfigDocs.render
    // The tenantDb description mentions ${tenant}_${tenantDb}; raw braces would break the MDX build.
    assert(!md.contains("${tenant}_${tenantDb}"), "raw curly braces must not reach the rendered page")
    assert(md.contains("&#123;tenant&#125;"), "curly braces in descriptions should be HTML-entity escaped")
  }
