package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import ai.starlake.quack.ondemand.federation.iceberg.{IcebergAuthType, IcebergRestConfig}
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
      secrets: Map[String, List[FederatedSecret]],
      catalogAliasOf: String => IO[Option[String]] = _ => IO.pure(None)
  ): FederationBlobBuilder =
    new FederationBlobBuilder(
      loadEnabled = _ => IO.pure(sources.filterNot(_.disabled)),
      loadSecrets = id => IO.pure(secrets.getOrElse(id, Nil)),
      resolver = resolver,
      catalogAliasOf = catalogAliasOf
    )

  "FederationBlobBuilder.build" should "return None when no enabled sources" in {
    builderWith(Nil, Map.empty).build("td-1").unsafeRunSync() shouldBe None
  }

  it should "substitute {{alias}} and {{secret.NAME}}" in {
    val s    = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
    val secs = Map(s.id -> List(secret(s.id, "PWD", "hunter2")))
    val blob = builderWith(List(s), secs).build("td-1").unsafeRunSync().value
    blob should include("ATTACH 'hunter2' AS fedpg;")
    blob should include("-- BEGIN federation: fedpg")
    blob should include("-- END federation: fedpg")
  }

  it should "skip disabled sources" in {
    val enabled  = src("fedpg", "ATTACH ... AS {{alias}};")
    val disabled = src("fedoff", "ATTACH ... AS {{alias}};", disabled = true)
    val out      = builderWith(List(enabled, disabled), Map.empty)
      .build("td-1")
      .unsafeRunSync()
      .value
    out should include("fedpg")
    out should not include "fedoff"
  }

  it should "reject unmatched {{...}} after substitution" in {
    val s  = src("fedpg", "ATTACH '{{secret.MISSPELLED}' AS {{alias}};")
    val ex = intercept[Throwable] {
      builderWith(List(s), Map.empty).build("td-1").unsafeRunSync()
    }
    ex.getMessage should (include("unsubstituted") or include("placeholder"))
  }

  it should "fail when a referenced secret has no row" in {
    val s  = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
    val ex = intercept[Throwable] {
      builderWith(List(s), Map.empty).build("td-1").unsafeRunSync()
    }
    ex.getMessage should include("PWD")
  }

  it should "order sources by alias for deterministic output" in {
    val a   = src("aaa", "-- a")
    val b   = src("bbb", "-- b")
    val out = builderWith(List(b, a), Map.empty).build("td-1").unsafeRunSync().value
    out.indexOf("BEGIN federation: aaa") should be < out.indexOf("BEGIN federation: bbb")
  }

  "FederationBlobBuilder.logSafePreview" should
    "keep {{secret.NAME}} placeholders while expanding {{alias}}" in {
      val s    = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
      val secs = Map(s.id -> List(secret(s.id, "PWD", "hunter2")))
      val safe = builderWith(List(s), secs).logSafePreview("td-1").unsafeRunSync().value
      safe should include("{{secret.PWD}}")
      safe should include("AS fedpg")
      safe should not include "hunter2"
    }

  it should "still reject missing secrets in preview mode" in {
    val s  = src("fedpg", "ATTACH '{{secret.PWD}}' AS {{alias}};")
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
    blob should include("PASSWORD 'O''Brien';")
  }

  it should "neutralize a hostile secret value that tries to close the literal" in {
    val hostile = "'); DROP TABLE qodstate_user;--"
    val s       = src("fedpg", "PASSWORD '{{secret.PWD}}';")
    val secs    = Map(s.id -> List(secret(s.id, "PWD", hostile)))
    val blob    = builderWith(List(s), secs).build("td-1").unsafeRunSync().value
    // After doubling apostrophes the entire hostile string is one big literal:
    //   PASSWORD '''); DROP TABLE qodstate_user;--';
    // The `DROP` is inside the literal, not a fresh statement.
    blob should include("PASSWORD '''); DROP TABLE qodstate_user;--';")
    blob should not include "');\nDROP" // sanity: no statement boundary leaked
  }

  it should "leave a secret with no apostrophes unchanged (no-op escape)" in {
    val s    = src("fedpg", "PASSWORD '{{secret.PWD}}';")
    val secs = Map(s.id -> List(secret(s.id, "PWD", "hunter2")))
    val blob = builderWith(List(s), secs).build("td-1").unsafeRunSync().value
    blob should include("PASSWORD 'hunter2';")
  }

  it should "also escape single quotes in the alias before splicing" in {
    val s    = src("o'fed", "-- alias: '{{alias}}'\nATTACH ... AS {{alias}};")
    val blob = builderWith(List(s), Map.empty).build("td-1").unsafeRunSync().value
    blob should include("-- alias: 'o''fed'")
    blob should include("AS o''fed;")
  }

  private def iceCfgJson = IcebergRestConfig(
    uri = "https://catalog.example.com/api/catalog",
    warehouse = "sales",
    authType = Some(IcebergAuthType.OAuth2),
    clientId = Some("{{secret.CID}}"),
    clientSecret = Some("{{secret.CSEC}}")
  ).toJson

  private def iceSrc(
      alias: String = "sales_lake",
      cfg: Option[String] = None,
      readOnly: Boolean = false
  ) =
    FederatedSource(
      id = "src-" + alias,
      tenantDbId = "td-1",
      alias = alias,
      setupSql = "",
      sourceType = ai.starlake.quack.model.FederatedSourceType.IcebergRest,
      config = cfg.orElse(Some(iceCfgJson)),
      readOnly = readOnly
    )

  private def iceSecrets(s: FederatedSource) =
    Map(s.id -> List(secret(s.id, "CID", "the-id"), secret(s.id, "CSEC", "the-secret")))

  it should "render an iceberg_rest source and substitute its secrets" in {
    val s    = iceSrc()
    val blob = builderWith(List(s), iceSecrets(s)).build("td-1").unsafeRunSync().value
    blob should include("INSTALL iceberg; LOAD iceberg;")
    blob should include("CLIENT_ID 'the-id'")
    blob should include("CLIENT_SECRET 'the-secret'")
    blob should not include "{{secret."
    blob should include("-- BEGIN federation: sales_lake")
    blob should include("-- END federation: sales_lake")
  }

  it should "keep iceberg secret placeholders unresolved in logSafePreview" in {
    val s    = iceSrc()
    val prev = builderWith(List(s), iceSecrets(s)).logSafePreview("td-1").unsafeRunSync().value
    prev should include("{{secret.CID}}")
    prev should not include "the-secret"
  }

  it should "fail loudly on an iceberg source whose config will not parse" in {
    val s   = iceSrc(cfg = Some("{ not json"))
    val err = intercept[RuntimeException](
      builderWith(List(s), Map.empty).build("td-1").unsafeRunSync()
    )
    err.getMessage should include("sales_lake")
  }

  it should "fail loudly on an iceberg source whose config parses but is invalid" in {
    // render has a validate-first precondition; the builder is the caller that honours it.
    val bad = iceSrc(cfg = Some(IcebergRestConfig(warehouse = "w").toJson))
    val err = intercept[RuntimeException](
      builderWith(List(bad), Map.empty).build("td-1").unsafeRunSync()
    )
    err.getMessage should include("exactly one")
  }

  it should "fail loudly on an iceberg source with no config at all" in {
    val s = iceSrc().copy(config = None)
    intercept[RuntimeException](builderWith(List(s), Map.empty).build("td-1").unsafeRunSync())
  }

  "FederationBlobBuilder.buildOne" should "render one source's block with secrets resolved" in {
    val s   = iceSrc()
    val out = builderWith(List(s), iceSecrets(s)).buildOne(s).unsafeRunSync()
    out.sql should include("CLIENT_ID 'the-id'")
    out.sql should include("-- BEGIN federation: sales_lake")
    // The block reports the values it substituted, which is what AttachErrorRedactor scrubs with.
    out.secretValues shouldBe Set("the-id", "the-secret")
  }

  it should "render neither the SQL nor the secret values when the block is stringified" in {
    // A case class's DERIVED toString prints every field, so `s"... $block"` or a logger's
    // implicit toString on this value would publish the resolved plaintext secrets and the SQL
    // that carries them -- the exact leak AttachErrorRedactor exists to prevent, arriving through
    // the component that resolved them. No caller does this today; this test is what keeps it
    // that way, so the override cannot be deleted as dead weight.
    val s     = iceSrc()
    val block = builderWith(List(s), iceSecrets(s)).buildOne(s).unsafeRunSync()
    block.secretValues should contain("the-secret")
    val rendered = s"federation block: $block"
    rendered should not include "the-secret"
    rendered should not include "the-id"
    rendered should not include "CLIENT_ID"
    rendered should include("2 values")
  }

  it should "render only the named source, not its siblings" in {
    val ice   = iceSrc()
    val other = src("fedpg", "ATTACH 'x' AS {{alias}};")
    val out   = builderWith(List(ice, other), iceSecrets(ice)).buildOne(ice).unsafeRunSync()
    out.sql should include("sales_lake")
    out.sql should not include "fedpg"
  }

  it should "escape a single quote inside a resolved iceberg secret" in {
    val s    = iceSrc()
    val secs = Map(s.id -> List(secret(s.id, "CID", "a"), secret(s.id, "CSEC", "O'Brien")))
    val out  = builderWith(List(s), secs).buildOne(s).unsafeRunSync()
    out.sql should include("CLIENT_SECRET 'O''Brien'")
    // The reported value is the RAW secret, not its SQL-escaped form: a catalog echoing the
    // credential back echoes what it was given, not what the SQL literal looked like.
    out.secretValues should contain("O'Brien")
  }

  // ---------- sibling alias reservation ----------
  // FederatedSourceHandlers.toSource enforces this same rule at REST/MCP write time, but
  // ManifestImporter builds FederatedSource rows directly and never goes through that handler, so
  // the blob builder is the one choke point every write path reaches. Without it a colliding
  // ATTACH fails silently on the node (the piped DuckDB CLI does not bail), and the operator only
  // sees "Catalog does not exist" at query time with no pointer back to the cause.

  it should "raise, naming the alias, when an iceberg source's alias collides with a sibling" in {
    val ice = iceSrc(alias = "sales_lake")
    // Distinct id: the `src` fixture derives id from the alias, so a same-alias sibling would
    // otherwise share `ice`'s id and be filtered out of its own sibling set.
    val other = src("sales_lake", "ATTACH 'x' AS {{alias}};").copy(id = "other-1")
    val err   = intercept[RuntimeException](
      builderWith(List(ice, other), iceSecrets(ice)).build("td-1").unsafeRunSync()
    )
    err.getMessage should include("sales_lake")
  }

  it should "raise on a sibling collision that differs only by case" in {
    val ice   = iceSrc(alias = "sales_lake")
    val other = src("SALES_LAKE", "ATTACH 'x' AS {{alias}};")
    intercept[RuntimeException](
      builderWith(List(ice, other), iceSecrets(ice)).build("td-1").unsafeRunSync()
    )
  }

  it should "still build both blocks when aliases do not collide" in {
    val ice   = iceSrc(alias = "sales_lake")
    val other = src("fedpg", "ATTACH 'x' AS {{alias}};")
    val blob  = builderWith(List(ice, other), iceSecrets(ice)).build("td-1").unsafeRunSync().value
    blob should include("-- BEGIN federation: sales_lake")
    blob should include("-- BEGIN federation: fedpg")
  }

  it should "not reserve any alias in buildOne, since collision is already settled at write time" in {
    val ice   = iceSrc(alias = "sales_lake")
    val other = src("sales_lake", "ATTACH 'x' AS {{alias}};")
    val out   = builderWith(List(ice, other), iceSecrets(ice)).buildOne(ice).unsafeRunSync()
    out.sql should include("-- BEGIN federation: sales_lake")
  }

  // ---------- tenant-db's own catalog alias reservation ----------
  // `IcebergRestConfig.validated`'s contract says callers should reserve the tenant-db's own
  // DuckDB catalog alias in addition to sibling federated aliases -- a manifest import can carry
  // an iceberg source aliased the same as its own tenant-db, which never goes through
  // `FederatedSourceHandlers.toSource`'s REST/MCP-time check.

  it should "raise, naming the alias, when an iceberg source's alias collides with its own tenant-db's catalog alias" in {
    val ice = iceSrc(alias = "sales")
    val err = intercept[RuntimeException](
      builderWith(List(ice), iceSecrets(ice), catalogAliasOf = _ => IO.pure(Some("sales")))
        .build("td-1")
        .unsafeRunSync()
    )
    err.getMessage should include("sales")
  }

  it should "raise on a tenant-db-alias collision that differs only by case" in {
    val ice = iceSrc(alias = "sales")
    intercept[RuntimeException](
      builderWith(List(ice), iceSecrets(ice), catalogAliasOf = _ => IO.pure(Some("SALES")))
        .build("td-1")
        .unsafeRunSync()
    )
  }

  it should "still build when the iceberg alias differs from the tenant-db's own catalog alias" in {
    val ice  = iceSrc(alias = "sales_lake")
    val blob =
      builderWith(List(ice), iceSecrets(ice), catalogAliasOf = _ => IO.pure(Some("sales")))
        .build("td-1")
        .unsafeRunSync()
        .value
    blob should include("-- BEGIN federation: sales_lake")
  }

  // ---------- read-only threading ----------
  // This is the test that proves `FederatedSource.readOnly` actually reaches the rendered ATTACH,
  // not just that `IcebergSetupSql.render` can produce READ_ONLY in isolation (that's
  // IcebergSetupSqlSpec's job).

  it should "carry READ_ONLY on the ATTACH for a stored source with readOnly = true" in {
    val s    = iceSrc(readOnly = true)
    val blob = builderWith(List(s), iceSecrets(s)).build("td-1").unsafeRunSync().value
    blob should include("READ_ONLY")
  }

  it should "emit no READ_ONLY for a stored source with readOnly = false" in {
    val s    = iceSrc(readOnly = false)
    val blob = builderWith(List(s), iceSecrets(s)).build("td-1").unsafeRunSync().value
    blob should not include "READ_ONLY"
  }
}
