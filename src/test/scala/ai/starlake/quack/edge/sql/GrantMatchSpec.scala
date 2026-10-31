package ai.starlake.quack.edge.sql

import ai.starlake.acl.parser.Verb
import ai.starlake.quack.ondemand.state.RolePermission
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GrantMatchSpec extends AnyFlatSpec with Matchers:

  private def p(cat: String, sch: String, tab: String, verb: String) =
    RolePermission("p", "r", cat, sch, tab, verb)

  "verbCovers" should "map the canonical grant verbs onto the collapsed access verbs" in {
    val table = List(
      ("RO", Verb.Read, true),
      ("RO", Verb.Write, false),
      ("RO", Verb.Ddl, false),
      ("RW", Verb.Read, true),
      ("RW", Verb.Write, true),
      ("RW", Verb.Ddl, false),
      ("DDL", Verb.Read, false),
      ("DDL", Verb.Write, false),
      ("DDL", Verb.Ddl, true),
      ("ALL", Verb.Read, true),
      ("ALL", Verb.Write, true),
      ("ALL", Verb.Ddl, true),
      ("ro", Verb.Read, true),
      ("XX", Verb.Read, false)
    )
    table.foreach((g, v, expected) =>
      withClue(s"$g/$v")(GrantMatch.verbCovers(g, v) shouldBe expected)
    )
  }

  "catalogMatch" should "admit a wildcard only on the session's catalogs, a literal anywhere" in {
    GrantMatch.catalogMatch("*", "acme_lake", Set("acme_lake")) shouldBe true
    GrantMatch.catalogMatch("*", "ACME_LAKE", Set("acme_lake")) shouldBe true
    GrantMatch.catalogMatch("*", "globex_lake", Set("acme_lake")) shouldBe false
    GrantMatch.catalogMatch("*", "acme_lake", Set.empty) shouldBe false
    GrantMatch.catalogMatch("globex_lake", "GLOBEX_LAKE", Set.empty) shouldBe true
  }

  "covers" should "need the verb, the catalog, the schema and the table to match" in {
    val perms = List(p("acme_lake", "main", "orders", "RO"), p("acme_lake", "*", "*", "DDL"))
    val cats  = Set("acme_lake")
    GrantMatch.covers(perms, cats, "acme_lake", "main", "orders", Verb.Read) shouldBe true
    GrantMatch.covers(perms, cats, "acme_lake", "main", "orders", Verb.Write) shouldBe false
    GrantMatch.covers(perms, cats, "acme_lake", "main", "sales", Verb.Read) shouldBe false
    GrantMatch.covers(perms, cats, "acme_lake", "x", "y", Verb.Ddl) shouldBe true
    GrantMatch.covers(perms, cats, "other", "main", "orders", Verb.Read) shouldBe false
    GrantMatch.covers(Nil, cats, "acme_lake", "main", "orders", Verb.Read) shouldBe false
  }

  "coversSchema" should "need a grant whose table part is the wildcard" in {
    val cats = Set("acme_lake")
    GrantMatch.coversSchema(
      List(p("acme_lake", "staging", "*", "DDL")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe true
    GrantMatch.coversSchema(
      List(p("acme_lake", "staging", "t", "DDL")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe false
    GrantMatch.coversSchema(
      List(p("acme_lake", "staging", "*", "RO")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe false
    GrantMatch.coversSchema(
      List(p("*", "*", "*", "ALL")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe true
    GrantMatch.coversSchema(
      List(p("*", "*", "*", "ALL")),
      cats,
      "globex_lake",
      "staging",
      Verb.Ddl
    ) shouldBe false
  }

  "hasWildcardAll" should "hold only for an unrestricted *.*.* ALL" in {
    GrantMatch.hasWildcardAll(List(p("*", "*", "*", "ALL"))) shouldBe true
    GrantMatch.hasWildcardAll(List(p("*", "*", "*", "all"))) shouldBe true
    GrantMatch.hasWildcardAll(List(p("*", "*", "*", "RW"))) shouldBe false
    GrantMatch.hasWildcardAll(List(p("acme_lake", "*", "*", "ALL"))) shouldBe false
  }
