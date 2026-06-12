package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FederationBlobBuilderSpec extends AnyFlatSpec with Matchers with OptionValues {

  private def src(alias: String, sql: String, disabled: Boolean = false) =
    FederatedSource("src-" + alias, "td-1", alias, sql, disabled = disabled)

  private def secret(srcId: String, name: String, value: String) =
    FederatedSecret(s"sec-$srcId-$name", srcId, name, Some(value), None)

  private val resolver = new PostgresSecretResolver()

  private def builderWith(
      sources: List[FederatedSource],
      secrets: Map[String, List[FederatedSecret]]
  ): FederationBlobBuilder =
    new FederationBlobBuilder(
      loadEnabled = _ => IO.pure(sources.filterNot(_.disabled)),
      loadSecrets = id => IO.pure(secrets.getOrElse(id, Nil)),
      resolver    = resolver
    )

  "FederationBlobBuilder.build" should "return None when no enabled sources" in {
    builderWith(Nil, Map.empty).build("td-1").unsafeRunSync() shouldBe None
  }

  it should "substitute {{alias}} and {{secret.NAME}}" in {
    val s    = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
    val secs = Map(s.id -> List(secret(s.id, "PWD", "hunter2")))
    val blob = builderWith(List(s), secs).build("td-1").unsafeRunSync().value
    blob should include ("ATTACH 'hunter2' AS fedpg;")
    blob should include ("-- BEGIN federation: fedpg")
    blob should include ("-- END federation: fedpg")
  }

  it should "skip disabled sources" in {
    val enabled  = src("fedpg",  "ATTACH ... AS {{alias}};")
    val disabled = src("fedoff", "ATTACH ... AS {{alias}};", disabled = true)
    val out = builderWith(List(enabled, disabled), Map.empty)
      .build("td-1").unsafeRunSync().value
    out should include     ("fedpg")
    out should not include "fedoff"
  }

  it should "reject unmatched {{...}} after substitution" in {
    val s = src("fedpg", "ATTACH '{{secret.MISSPELLED}' AS {{alias}};")
    val ex = intercept[Throwable] {
      builderWith(List(s), Map.empty).build("td-1").unsafeRunSync()
    }
    ex.getMessage should (include("unsubstituted") or include("placeholder"))
  }

  it should "fail when a referenced secret has no row" in {
    val s = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
    val ex = intercept[Throwable] {
      builderWith(List(s), Map.empty).build("td-1").unsafeRunSync()
    }
    ex.getMessage should include("PWD")
  }

  it should "order sources by alias for deterministic output" in {
    val a = src("aaa", "-- a")
    val b = src("bbb", "-- b")
    val out = builderWith(List(b, a), Map.empty).build("td-1").unsafeRunSync().value
    out.indexOf("BEGIN federation: aaa") should be < out.indexOf("BEGIN federation: bbb")
  }

  "FederationBlobBuilder.logSafePreview" should
    "keep {{secret.NAME}} placeholders while expanding {{alias}}" in {
    val s    = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
    val secs = Map(s.id -> List(secret(s.id, "PWD", "hunter2")))
    val safe = builderWith(List(s), secs).logSafePreview("td-1").unsafeRunSync().value
    safe should include     ("{{secret.PWD}}")
    safe should include     ("AS fedpg")
    safe should not include "hunter2"
  }

  it should "still reject missing secrets in preview mode" in {
    val s = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
    val ex = intercept[Throwable] {
      builderWith(List(s), Map.empty).logSafePreview("td-1").unsafeRunSync()
    }
    ex.getMessage should include("PWD")
  }

  // ---------- SQL-safety of substituted values ----------
  // The production template wraps secrets in a single-quoted SQL literal
  // (PASSWORD '{{secret.PG_PWD}}'). A naive substitution would break or
  // injection-attack the moment the value contained an apostrophe.
  // [[sqlEscapeSingleQuote]] doubles each `'` so the value survives intact.

  "build" should "double single quotes in a secret value so the surrounding literal stays valid" in {
    val s    = src("fedpg", "PASSWORD '{{secret.PWD}}';")
    val secs = Map(s.id -> List(secret(s.id, "PWD", "O'Brien")))
    val blob = builderWith(List(s), secs).build("td-1").unsafeRunSync().value
    // SQL-literal escaped form: 'O''Brien'  (DuckDB parses to O'Brien)
    blob should include ("PASSWORD 'O''Brien';")
  }

  it should "neutralize a hostile secret value that tries to close the literal" in {
    val hostile = "'); DROP TABLE qodstate_user;--"
    val s       = src("fedpg", "PASSWORD '{{secret.PWD}}';")
    val secs    = Map(s.id -> List(secret(s.id, "PWD", hostile)))
    val blob    = builderWith(List(s), secs).build("td-1").unsafeRunSync().value
    // After doubling apostrophes the entire hostile string is one big literal:
    //   PASSWORD '''); DROP TABLE qodstate_user;--';
    // The `DROP` is inside the literal, not a fresh statement.
    blob should include ("PASSWORD '''); DROP TABLE qodstate_user;--';")
    blob should not include "');\nDROP"  // sanity: no statement boundary leaked
  }

  it should "leave a secret with no apostrophes unchanged (no-op escape)" in {
    val s    = src("fedpg", "PASSWORD '{{secret.PWD}}';")
    val secs = Map(s.id -> List(secret(s.id, "PWD", "hunter2")))
    val blob = builderWith(List(s), secs).build("td-1").unsafeRunSync().value
    blob should include ("PASSWORD 'hunter2';")
  }

  it should "also escape single quotes in the alias before splicing" in {
    val s    = src("o'fed", "-- alias: '{{alias}}'\nATTACH ... AS {{alias}};")
    val blob = builderWith(List(s), Map.empty).build("td-1").unsafeRunSync().value
    blob should include ("-- alias: 'o''fed'")
    blob should include ("AS o''fed;")
  }
}