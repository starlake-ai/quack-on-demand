package ai.starlake.quack.boot

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import ai.starlake.quack.ondemand.state.{FederatedSourceOps, InMemoryFederatedSourceStore}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[BootPreflight.invalidFederatedAliases]] / [[BootPreflight.checkFederatedAliases]]: a
  * best-effort boot report of federated source aliases that [[ai.starlake.quack.model.Names]]'s
  * identifier rule now rejects (a hyphen or dot, mixed case that no longer round-trips, or an
  * over-length alias), added alongside typed iceberg_rest sources. An operator carrying such a
  * legacy alias should learn about it on restart rather than on their next edit attempt, but a
  * legacy install must still be allowed to boot.
  *
  * The decision (which aliases are invalid) lives in the pure, total `invalidFederatedAliases`,
  * asserted here directly on its return value -- no logging harness needed. `checkFederatedAliases`
  * is the thin logging wrapper around it; only its no-throw property is asserted here.
  */
class BootPreflightFederatedAliasesSpec extends AnyFlatSpec with Matchers:

  private val throwing = new FederatedSourceOps:
    def upsertSource(s: FederatedSource): Unit                                = ()
    def deleteSource(id: String): Unit                                        = ()
    def getSource(tenantDbId: String, alias: String): Option[FederatedSource] = None
    def listSources(tenantDbId: String): List[FederatedSource]                = Nil
    def upsertSecret(s: FederatedSecret): Unit                                = ()
    def deleteSecret(sourceId: String, name: String): Unit                    = ()
    def getSecret(sourceId: String, name: String): Option[FederatedSecret]    = None
    def listSecrets(sourceId: String): List[FederatedSecret]                  = Nil
    def tenantDbIdsWithSources(): Set[String] = throw new RuntimeException("boom")

  "invalidFederatedAliases" should
    "return exactly the invalid pair when a store has one invalid and one valid alias" in {
      val store = new InMemoryFederatedSourceStore
      store.upsertSource(
        FederatedSource(
          id = "fs-1",
          tenantDbId = "td-1",
          alias = "ext-s3",
          setupSql = "ATTACH ... AS ext-s3;"
        )
      )
      store.upsertSource(
        FederatedSource(
          id = "fs-2",
          tenantDbId = "td-1",
          alias = "ext_s3",
          setupSql = "ATTACH ... AS ext_s3;"
        )
      )
      BootPreflight.invalidFederatedAliases(store) shouldBe List(("td-1", "ext-s3"))
    }

  it should "return Nil when every alias is valid" in {
    val store = new InMemoryFederatedSourceStore
    store.upsertSource(
      FederatedSource(
        id = "fs-1",
        tenantDbId = "td-1",
        alias = "ext_s3",
        setupSql = "ATTACH ... AS ext_s3;"
      )
    )
    BootPreflight.invalidFederatedAliases(store) shouldBe Nil
  }

  it should "return Nil, not propagate, when the store's enumeration throws" in {
    BootPreflight.invalidFederatedAliases(throwing) shouldBe Nil
  }

  "checkFederatedAliases" should "not propagate when the store's enumeration throws" in {
    noException should be thrownBy BootPreflight.checkFederatedAliases(throwing)
  }
