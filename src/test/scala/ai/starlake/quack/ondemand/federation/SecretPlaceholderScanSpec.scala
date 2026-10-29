package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CONTRACT Addendum 4 item 29 (amended): every `{{secret.NAME}}` of a `sql` source's template must
  * sit inside a single-quoted literal. The scan against templates that try to look quoted while
  * DuckDB would read the placeholder outside a literal, and the render-time rule for templates
  * stored before the rule.
  */
class SecretPlaceholderScanSpec extends AnyFlatSpec with Matchers with OptionValues:
  import SecretPlaceholderScan.unquoted

  "the scan" should "admit a placeholder inside a plain single-quoted literal" in {
    unquoted("CREATE SECRET s (TYPE postgres, PASSWORD '{{secret.P}}');") shouldBe Nil
    unquoted(
      "ATTACH 'host=h user={{secret.U}} password={{secret.P}}' AS {{alias}} (TYPE postgres);"
    ) shouldBe
      Nil
    // Doubled quotes stay inside the literal.
    unquoted("SELECT 'it''s {{secret.P}} '' still in';") shouldBe Nil
    // A quoted identifier, a comment or a $ inside a literal does not end it.
    unquoted("""SELECT '"x" -- /* $1 {{secret.P}}';""") shouldBe Nil
    // Comments and identifiers before a literal do not open one.
    unquoted("/* ' */ \"a\"\"b\" -- '\nSELECT '{{secret.P}}';") shouldBe Nil
    unquoted("SELECT 1;") shouldBe Nil
  }

  it should "refuse a placeholder anywhere else" in {
    unquoted("ATTACH 'x' AS {{alias}} (PORT {{secret.PORT}});") shouldBe List("PORT")
    unquoted("SELECT \"{{secret.P}}\";") shouldBe List("P")
    unquoted("SELECT `{{secret.P}}`;") shouldBe List("P")
    unquoted("SELECT 1; -- {{secret.P}}") shouldBe List("P")
    unquoted("SELECT 1 /* {{secret.P}} */;") shouldBe List("P")
    // A quote inside a line comment opens nothing.
    unquoted("-- '\n{{secret.P}} '") shouldBe List("P")
    // Block comments nest as in DuckDB: the first */ does not end the comment, so the quote after it
    // is commented out and the placeholder stands outside any literal.
    unquoted("/* /* */ ' */ {{secret.P}} '") shouldBe List("P")
    // Concatenation around a bare placeholder.
    unquoted("SELECT 'it''s' || {{secret.P}} || '{{secret.Q}}';") shouldBe List("P")
    // The same name once quoted, once not.
    unquoted("SELECT '{{secret.P}}', {{secret.P}};") shouldBe List("P")
  }

  it should "fail closed on escape strings, dollar quoting and unclosed literals" in {
    // In E'...' a value ending with a backslash would escape the closing quote.
    unquoted("SELECT E'{{secret.P}}';") shouldBe List("P")
    unquoted("SELECT e'x', '{{secret.Q}}';") shouldBe List("Q")
    // A dollar-quoted string or a parameter outside literals: every placeholder is unquoted.
    unquoted("SELECT $$x$$, '{{secret.Q}}';") shouldBe List("Q")
    unquoted("SELECT $1, '{{secret.Q}}';") shouldBe List("Q")
    unquoted("SELECT $tag$ '{{secret.Q}}' $tag$;") shouldBe List("Q")
    // A literal never closed.
    unquoted("SELECT '{{secret.P}}") shouldBe List("P")
    unquoted("SELECT 'a'' {{secret.P}}") shouldBe List("P")
  }

  it should "refuse it at save, naming the placeholder, for every writer" in {
    val sql  = FederatedSource("s1", "td", "fed", "ATTACH 'x' AS fed (PORT {{secret.PORT}});")
    val errs = FederatedSourceRules.errors(sql, Set.empty)
    errs.exists(_.contains("{{secret.PORT}} outside a single-quoted literal")) shouldBe true
    FederatedSourceRules.errors(
      sql.copy(setupSql = "ATTACH '{{secret.PORT}}' AS fed;"),
      Set.empty
    ) shouldBe
      Nil
  }

  // ---- render: a template stored before the rule
  private def builder(sources: List[FederatedSource], secrets: Map[String, String]) =
    new FederationBlobBuilder(
      loadEnabled = _ => IO.pure(sources),
      loadSecrets = id =>
        IO.pure(secrets.toList.map((n, v) => FederatedSecret(s"sec-$id-$n", id, n, Some(v), None))),
      resolver = new PostgresSecretResolver(),
      catalogAliasOf = _ => IO.pure(None)
    )

  private val Legacy =
    FederatedSource(
      "src-legacy",
      "td-1",
      "legacy",
      "ATTACH 'h' AS {{alias}} (PORT {{secret.PORT}});"
    )
  private val Sibling = FederatedSource("src-ok", "td-1", "ok", "ATTACH 'o' AS {{alias}};")

  "a stored template" should "substitute an unquoted placeholder holding a plain token" in {
    val blob =
      builder(List(Legacy, Sibling), Map("PORT" -> "5432")).build("td-1").unsafeRunSync().value
    blob should include("(PORT 5432);")
  }

  it should "leave out only its own block when the value is anything else, never logging it" in {
    val value          = "5432); COPY (SELECT 1) TO '/tmp/AKIAQODSCANMARKER"
    val (blob, logged) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture
      .logged(classOf[FederationBlobBuilder].getName)(
        builder(List(Legacy, Sibling), Map("PORT" -> value)).build("td-1").unsafeRunSync()
      )
    blob.value should include("-- BEGIN federation: ok")
    blob.value should not include "legacy"
    blob.value should not include "AKIAQODSCANMARKER"
    val lines = logged.map(_.getFormattedMessage)
    lines.exists(l => l.contains("'legacy'") && l.contains("{{secret.PORT}}")) shouldBe true
    lines.exists(_.contains("AKIAQODSCANMARKER")) shouldBe false
  }

  it should "be named by the boot report, never with its value or SQL" in {
    val td = ai.starlake.quack.model.TenantDb(
      "td-1",
      "acme",
      "acme_prod",
      ai.starlake.quack.model.TenantDbKind.InMemory,
      Map.empty,
      ""
    )
    val lines = ai.starlake.quack.ondemand.initsql.InitSqlTrustReport
      .unquotedSecretLines(List(td -> Legacy, td -> Sibling))
    lines should have size 1
    lines.head should include("federated source 'legacy'")
    lines.head should include("{{secret.PORT}}")
    lines.head should not include "ATTACH"
  }
