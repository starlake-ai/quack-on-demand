package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale

/** The one alias fold, and the read-only set built on it.
  *
  * The fold decides whether `CatalogWriteScreen`'s `denied.contains(...)` matches. Its other side
  * is produced by the ACL parser, which lowercases with `Locale.ROOT`, and the comparison is exact
  * string equality. So a fold that consults the JVM default locale takes a read-only catalog whose
  * alias holds an ASCII `I` out of the denied set on a `tr` or `az` JVM, and the write it was
  * supposed to refuse is admitted.
  */
class FederatedAliasSpec extends AnyFlatSpec with Matchers:

  /** Runs `body` with the JVM default locale forced to Turkish, restoring the previous default on
    * every path. `Locale.setDefault` is process-global, so the restore is not optional.
    */
  private def withTurkishLocale[A](body: => A): A =
    val previous = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try body
    finally Locale.setDefault(previous)

  private def source(alias: String, readOnly: Boolean, disabled: Boolean = false) =
    FederatedSource(
      id = "fs-1",
      tenantDbId = "td-1",
      alias = alias,
      readOnly = readOnly,
      disabled = disabled
    )

  "fold" should "lowercase an ASCII I even under a Turkish default locale" in
    withTurkishLocale {
      withClue("the forced default locale must fold the ASCII i, or this test proves nothing: ") {
        "I".toLowerCase should not be "i"
        "I".toLowerCase(Locale.ROOT) shouldBe "i"
      }
      FederatedAlias.fold("SALES_I") shouldBe "sales_i"
    }

  it should "agree with Names.normalizeOrError, which is what the write path stores" in
    // The two are separate calls in separate objects; what makes the boot-time
    // "not lowercase" check and the runtime denial agree is that both land on Locale.ROOT.
    withTurkishLocale {
      Names.normalizeOrError("Sales_I", "alias") shouldBe Right(FederatedAlias.fold("Sales_I"))
    }

  "readOnlySet" should "carry only the read-only aliases, folded" in {
    val sources = List(
      source("SALES_I", readOnly = true),
      source("Writable", readOnly = false)
    )
    withTurkishLocale {
      FederatedAlias.readOnlySet(sources) shouldBe Set("sales_i")
    }
  }

  it should "keep a DISABLED read-only source in the set" in {
    // A disabled source's alias stays ATTACHed on running nodes until the pool recycles, so its
    // read-only flag must keep applying until then. Dropping disabled rows here would unlock a
    // catalog the engine still has attached.
    FederatedAlias.readOnlySet(
      List(source("sales_lake", readOnly = true, disabled = true))
    ) shouldBe Set("sales_lake")
  }

  it should "be empty when nothing is read-only" in {
    FederatedAlias.readOnlySet(List(source("sales_lake", readOnly = false))) shouldBe empty
  }
