package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{
  FederatedSource,
  FederatedSourceType,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.federation.iceberg.{
  IcebergAuthType,
  IcebergEndpointType,
  IcebergRestConfig
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{
  FederatedSourceStore,
  LiquibaseRunner,
  PostgresControlPlaneStore
}
import cats.effect.unsafe.implicits.global
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import scala.util.Try

class FederatedSourceHandlersSpec
    extends AnyFlatSpec
    with Matchers
    with OptionValues
    with EitherValues:

  TestPostgres.dropStrayTestDatabases("qodh")

  /** Yields the store + (tenantName, tenantDbName) => tenantDbId resolver over a freshly-migrated
    * throwaway Postgres, so a test can build a handler with a custom `scopeOf`.
    */
  private def withEnv(
      test: (FederatedSourceStore, (String, String) => Option[String], String) => Unit
  ): Unit =
    if !TestPostgres.reachable then
      cancel(
        s"local Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}; skipping"
      )
    val dbName = s"qodh_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url  = TestPostgres.dbUrl(dbName)
      val user = TestPostgres.pgUser
      val pass = TestPostgres.pgPass
      new LiquibaseRunner(url, user, pass).run()
      val cp = new PostgresControlPlaneStore(url, user, pass)
      // Seed a tenant + tenant-db. The handlers are called with tenant "acme"
      // and the resolver matches on tenant id, so the id IS the slug "acme"
      // (post slug-id refactor: the slug is the one tenant key).
      cp.upsertTenant(Tenant(id = "acme", displayName = "acme", disabled = false))
      cp.upsertTenantDb(
        TenantDb(
          id = "td-1",
          tenantId = "acme",
          name = "acme_prod",
          kind = TenantDbKind.InMemory,
          metastore = Map.empty,
          dataPath = ""
        )
      )
      val fs = new FederatedSourceStore(url, user, pass)
      // resolver: looks up tenantDbId from (tenantName, tenantDbName)
      val resolver: (String, String) => Option[String] = (tenantName, tenantDbName) =>
        cp.listTenants().find(_.id == tenantName).flatMap { t =>
          cp.listTenantDbs(t.id).find(_.name == tenantDbName).map(_.id)
        }
      test(fs, resolver, "td-1")
    finally Try(TestPostgres.dropDatabase(dbName))

  private def withHandlers(test: (FederatedSourceHandlers, String) => Unit): Unit =
    withEnv((fs, resolver, tdId) =>
      test(
        new FederatedSourceHandlers(
          fs,
          resolver,
          scopeOf = _ => SessionScope.Superuser,
          invalidateSources = _ => (),
          publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
          catalogAliasOf = _ => None
        ),
        tdId
      )
    )

  // 1. POST source -> 200, GET it back, alias matches
  "FederatedSourceHandlers.createSource" should
    "create a source and retrieve it by alias" in withHandlers { (h, _) =>
      val created = h
        .createSource(
          "acme",
          "acme_prod",
          FederatedSourceCreateRequest(alias = "fedpg", setupSql = Some("INSTALL postgres;")),
          None
        )
        .unsafeRunSync()
      created.isRight shouldBe true
      val r = created.toOption.value
      r.alias shouldBe "fedpg"
      r.setupSql shouldBe Some("INSTALL postgres;")

      val got = h.getSource("acme", "acme_prod", "fedpg").unsafeRunSync()
      got.isRight shouldBe true
      got.toOption.value.alias shouldBe "fedpg"
    }

  // 2. POST source twice with same alias -> upsert, still one row
  it should "upsert on duplicate alias (update in place)" in withHandlers { (h, _) =>
    h.createSource(
      "acme",
      "acme_prod",
      FederatedSourceCreateRequest(alias = "dup", setupSql = Some("v1")),
      None
    ).unsafeRunSync()
    val second = h
      .createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "dup", setupSql = Some("v2")),
        None
      )
      .unsafeRunSync()
    second.isRight shouldBe true
    second.toOption.value.setupSql shouldBe Some("v2")

    val list = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
    list.sources.count(_.alias == "dup") shouldBe 1
  }

  // 3. POST secret with value -> 200; GET secrets list shows value redacted
  "FederatedSourceHandlers.upsertSecret" should
    "store a secret and return it with value redacted" in withHandlers { (h, _) =>
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "src1", setupSql = Some("SELECT 1")),
        None
      ).unsafeRunSync()

      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "src1",
          FederatedSecretUpsertRequest(name = "PG_PASSWORD", value = Some("hunter2")),
          None
        )
        .unsafeRunSync()
      r.isRight shouldBe true
      r.toOption.value.value shouldBe Some("***REDACTED***")

      val list = h.listSecrets("acme", "acme_prod", "src1").unsafeRunSync().toOption.value
      list.secrets should have size 1
      list.secrets.head.name shouldBe "PG_PASSWORD"
      list.secrets.head.value shouldBe Some("***REDACTED***")
    }

  // Bonus: deleteSource removes the source
  "FederatedSourceHandlers.deleteSource" should
    "remove an existing source" in withHandlers { (h, _) =>
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "todel", setupSql = Some("SELECT 1")),
        None
      ).unsafeRunSync()
      val del = h.deleteSource("acme", "acme_prod", "todel", None).unsafeRunSync()
      del.isRight shouldBe true

      val list = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
      list.sources.exists(_.alias == "todel") shouldBe false
    }

  it should "return 404 for an unknown alias" in withHandlers { (h, _) =>
    val del = h.deleteSource("acme", "acme_prod", "ghost", None).unsafeRunSync()
    del.isLeft shouldBe true
    del.swap.toOption.value._1.code shouldBe 404
  }

  // Bonus: unknown tenant-db -> 404
  "FederatedSourceHandlers.listSources" should
    "return 404 for an unknown tenant-db" in withHandlers { (h, _) =>
      val r = h.listSources("acme", "no_such_db").unsafeRunSync()
      r.isLeft shouldBe true
      r.swap.toOption.value._1.code shouldBe 404
    }

  // --- externalRef secret authoring is superuser-only -----------------------
  // Regression guard for the privilege-escalation finding: a tenant admin
  // must not be able to author an `env:` / KMS externalRef secret (which
  // resolves from the MANAGER's own trust domain at spawn), while a superuser
  // and a value-backed tenant secret both stay allowed.

  private val AdminTok = "admin-token"
  private val SuperTok = "super-token"
  // Through the production resolver, so an absent or unknown credential is NoAccess.
  private val scopes: SessionScope.Resolver = SessionScope.resolver(
    Map(
      AdminTok -> SessionScope(superuser = false, manageableTenants = Set("acme")),
      SuperTok -> SessionScope.Superuser
    ).get
  )

  private def seedSource(fs: FederatedSourceStore, resolver: (String, String) => Option[String])(
      scopeOf: SessionScope.Resolver
  ): FederatedSourceHandlers =
    val h = new FederatedSourceHandlers(
      fs,
      resolver,
      scopeOf = scopeOf,
      invalidateSources = _ => (),
      publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
      catalogAliasOf = _ => None
    )
    h.createSource(
      "acme",
      "acme_prod",
      FederatedSourceCreateRequest(alias = "fedpg", setupSql = Some("INSTALL postgres;")),
      // A sql source is a superuser's to write (CONTRACT Addendum 4 item 26).
      Some(SuperTok)
    ).unsafeRunSync()
      .isRight shouldBe true
    h

  "FederatedSourceHandlers.upsertSecret" should
    "reject an externalRef secret from a non-superuser (tenant admin) session with 403" in
    withEnv { (fs, resolver, _) =>
      val h = seedSource(fs, resolver)(scopes)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(
            name = "X",
            externalRef = Some("env:QOD_SESSION_JWT_SECRET")
          ),
          Some(AdminTok)
        )
        .unsafeRunSync()
      r.isLeft shouldBe true
      val (code, err) = r.swap.toOption.value
      code.code shouldBe 403
      err.error shouldBe "superuser_required"
      // Nothing was written.
      h.listSecrets("acme", "acme_prod", "fedpg")
        .unsafeRunSync()
        .toOption
        .value
        .secrets shouldBe empty
    }

  it should "allow a value-backed secret from a non-superuser (tenant admin) session" in
    withEnv { (fs, resolver, _) =>
      val h = seedSource(fs, resolver)(scopes)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(name = "PW", value = Some("my-own-pw")),
          Some(AdminTok)
        )
        .unsafeRunSync()
      r.isRight shouldBe true
    }

  it should "allow an externalRef secret from a superuser session" in
    withEnv { (fs, resolver, _) =>
      val h = seedSource(fs, resolver)(scopes)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(name = "X", externalRef = Some("env:SL_QOD_SECRET_FOO")),
          Some(SuperTok)
        )
        .unsafeRunSync()
      r.isRight shouldBe true
    }

  it should "refuse every secret from a caller with no credential (no trusted arm)" in
    withEnv { (fs, resolver, _) =>
      // No credential resolves to NoAccess, never to an operator: the tenant gate refuses it
      // before the externalRef rule is reached, and nothing is written.
      val h = seedSource(fs, resolver)(scopes)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(name = "X", externalRef = Some("env:SL_QOD_SECRET_FOO")),
          None
        )
        .unsafeRunSync()
      r.left.toOption.map((code, err) => (code.code, err.error)) shouldBe
        Some((403, "tenant_forbidden"))
      h.listSecrets("acme", "acme_prod", "fedpg")
        .unsafeRunSync()
        .toOption
        .value
        .secrets shouldBe empty
    }

  it should "reject a blank inline value with a 400 naming the secret" in withHandlers { (h, _) =>
    h.createSource(
      "acme",
      "acme_prod",
      FederatedSourceCreateRequest(alias = "blanksec", setupSql = Some("SELECT 1")),
      None
    ).unsafeRunSync()
    val r = h
      .upsertSecret(
        "acme",
        "acme_prod",
        "blanksec",
        FederatedSecretUpsertRequest(name = "PG_PASSWORD", value = Some("   ")),
        None
      )
      .unsafeRunSync()
    r.isLeft shouldBe true
    val (code, err) = r.swap.toOption.value
    code.code shouldBe 400
    err.message should include("PG_PASSWORD")

    val list = h.listSecrets("acme", "acme_prod", "blanksec").unsafeRunSync().toOption.value
    list.secrets shouldBe empty
  }

  // --- Typed iceberg_rest sources on the REST surface ------------------------

  private def iceReq(
      alias: String = "sales_lake",
      cfg: IcebergRestConfig = IcebergRestConfig(
        uri = "https://catalog.example.com/api/catalog",
        warehouse = "sales",
        authType = Some(IcebergAuthType.OAuth2),
        clientId = Some("{{secret.CID}}"),
        clientSecret = Some("{{secret.CSEC}}")
      ),
      readOnly: Option[Boolean] = None
  ) = FederatedSourceCreateRequest(
    alias = alias,
    sourceType = Some("iceberg_rest"),
    config = Some(cfg),
    readOnly = readOnly
  )

  "createSource" should "accept a typed iceberg source and default it to read-only" in withHandlers {
    (h, _) =>
      val res = h.createSource("acme", "acme_prod", iceReq(), None).unsafeRunSync()
      val out = res.toOption.value
      out.sourceType shouldBe "iceberg_rest"
      out.readOnly shouldBe true
      out.config.value.warehouse shouldBe "sales"
      out.setupSql shouldBe None
  }

  it should "honour an explicit readOnly=false on an iceberg source" in withHandlers { (h, _) =>
    val res = h
      .createSource("acme", "acme_prod", iceReq(readOnly = Some(false)), None)
      .unsafeRunSync()
    res.toOption.value.readOnly shouldBe false
  }

  it should "keep a sql source writable by default" in withHandlers { (h, _) =>
    val req = FederatedSourceCreateRequest(alias = "pg_src", setupSql = Some("ATTACH 'x';"))
    val out = h.createSource("acme", "acme_prod", req, None).unsafeRunSync().toOption.value
    out.sourceType shouldBe "sql"
    out.readOnly shouldBe false
  }

  it should "400 on an unknown sourceType" in withHandlers { (h, _) =>
    val req         = iceReq().copy(sourceType = Some("hive_metastore"))
    val (code, err) = h.createSource("acme", "acme_prod", req, None).unsafeRunSync().left.value
    code shouldBe StatusCode.BadRequest
    err.message should include("hive_metastore")
  }

  it should "400 on an iceberg source with no config" in withHandlers { (h, _) =>
    val req         = iceReq().copy(config = None)
    val (code, err) = h.createSource("acme", "acme_prod", req, None).unsafeRunSync().left.value
    code shouldBe StatusCode.BadRequest
    err.message should include("config")
  }

  it should "400 on an iceberg source carrying setupSql as well" in withHandlers { (h, _) =>
    val req       = iceReq().copy(setupSql = Some("ATTACH 'x';"))
    val (code, _) = h.createSource("acme", "acme_prod", req, None).unsafeRunSync().left.value
    code shouldBe StatusCode.BadRequest
  }

  it should "400 naming the DuckDB rule when authType and endpointType are both set" in withHandlers {
    (h, _) =>
      val cfg = IcebergRestConfig(
        uri = "https://c",
        warehouse = "w",
        authType = Some(IcebergAuthType.OAuth2),
        endpointType = Some(IcebergEndpointType.Glue),
        clientId = Some("a"),
        clientSecret = Some("b")
      )
      val (code, err) = h
        .createSource("acme", "acme_prod", iceReq(cfg = cfg), None)
        .unsafeRunSync()
        .left
        .value
      code shouldBe StatusCode.BadRequest
      err.message should include("exactly one")
  }

  it should "400 on an alias that collides with the tenant-db's own catalog alias" in
    withEnv { (fs, resolver, _) =>
      val h = new FederatedSourceHandlers(
        fedStore = fs,
        resolver = resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => Some("acme_db")
      )
      val (code, err) = h
        .createSource("acme", "acme_prod", iceReq(alias = "acme_db"), None)
        .unsafeRunSync()
        .left
        .value
      code shouldBe StatusCode.BadRequest
      err.message should include("reserved")
    }

  it should "400 on an alias that collides with a sibling federated source" in withHandlers {
    (h, _) =>
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "pg_src", setupSql = Some("ATTACH 'x';")),
        None
      ).unsafeRunSync()
      val (code, _) = h
        .createSource("acme", "acme_prod", iceReq(alias = "pg_src"), None)
        .unsafeRunSync()
        .left
        .value
      code shouldBe StatusCode.BadRequest
  }

  it should "400 on an alias that collides with a DuckDB builtin" in withHandlers { (h, _) =>
    val (code, err) = h
      .createSource("acme", "acme_prod", iceReq(alias = "memory"), None)
      .unsafeRunSync()
      .left
      .value
    code shouldBe StatusCode.BadRequest
    err.message should include("reserved")
  }

  it should "400 on an alias that is not a plain identifier" in withHandlers { (h, _) =>
    val (code, _) = h
      .createSource("acme", "acme_prod", iceReq(alias = "sales-lake"), None)
      .unsafeRunSync()
      .left
      .value
    code shouldBe StatusCode.BadRequest
  }

  "createSource" should "normalize a mixed-case alias to lowercase for every source type" in
    withHandlers { (h, _) =>
      val ice = h
        .createSource("acme", "acme_prod", iceReq(alias = "Sales_Lake"), None)
        .unsafeRunSync()
        .toOption
        .value
      ice.alias shouldBe "sales_lake"
      val sql = h
        .createSource(
          "acme",
          "acme_prod",
          FederatedSourceCreateRequest(alias = "PG_Src", setupSql = Some("ATTACH 'x';")),
          None
        )
        .unsafeRunSync()
        .toOption
        .value
      sql.alias shouldBe "pg_src"
    }

  it should "400 on an over-length alias, naming the 63-char bound" in withHandlers { (h, _) =>
    val (code, err) = h
      .createSource("acme", "acme_prod", iceReq(alias = "a" * 64), None)
      .unsafeRunSync()
      .left
      .value
    code shouldBe StatusCode.BadRequest
    err.message should include("63")
  }

  it should "upsert onto the existing row when only the alias case differs" in withHandlers {
    (h, _) =>
      h.createSource("acme", "acme_prod", iceReq(alias = "sales_lake"), None).unsafeRunSync()
      h.createSource("acme", "acme_prod", iceReq(alias = "SALES_LAKE"), None).unsafeRunSync()
      h.listSources("acme", "acme_prod")
        .unsafeRunSync()
        .toOption
        .value
        .sources
        .count(_.alias == "sales_lake") shouldBe 1
  }

  // --- C1: a legacy (pre-normalization) mixed-case alias is rewritten in place, not duplicated --

  it should "rewrite a legacy mixed-case row in place when re-POSTed under its own alias" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-legacy-1",
          tenantDbId = tdId,
          alias = "extS3",
          setupSql = "ATTACH 'x';"
        )
      )
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "extS3", setupSql = Some("ATTACH 'y';")),
        None
      ).unsafeRunSync()
        .isRight shouldBe true

      val sources = fs.listSources(tdId)
      sources.count(_.alias.equalsIgnoreCase("exts3")) shouldBe 1
      val row = sources.find(_.alias.equalsIgnoreCase("exts3")).value
      row.alias shouldBe "exts3"
      row.id shouldBe "fs-legacy-1"
    }

  it should "rewrite a legacy mixed-case row in place when re-POSTed under a DIFFERENT case" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-legacy-2",
          tenantDbId = tdId,
          alias = "extS3",
          setupSql = "ATTACH 'x';"
        )
      )
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "EXTS3", setupSql = Some("ATTACH 'y';")),
        None
      ).unsafeRunSync()
        .isRight shouldBe true

      val sources = fs.listSources(tdId)
      sources.count(_.alias.equalsIgnoreCase("exts3")) shouldBe 1
      val row = sources.find(_.alias.equalsIgnoreCase("exts3")).value
      row.alias shouldBe "exts3"
      row.id shouldBe "fs-legacy-2"
    }

  // --- a legacy alias the identifier rule REJECTS stays editable under its stored spelling ---
  //
  // `Names.normalizeOrError` now runs on every federated alias, `sql` sources included. A row
  // created before that (`ext-s3`, a hyphen) has no normalized form, so without a grandfather
  // clause it could not be updated through REST, CLI, MCP or manifest import at all -- and since
  // the alias is the catalog segment of every RolePermission, delete-and-recreate also means
  // re-granting. The clause is deliberately narrow: it only ever RESOLVES to a stored row's own
  // spelling, so it cannot mint a new invalid alias (the "not a plain identifier" case above,
  // which has no stored row, is the arm that pins that and still 400s).

  it should "update a legacy hyphenated row in place, keeping its stored alias" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-legacy-hyphen",
          tenantDbId = tdId,
          alias = "ext-s3",
          setupSql = "ATTACH 'x';"
        )
      )
      val out = h
        .createSource(
          "acme",
          "acme_prod",
          FederatedSourceCreateRequest(alias = "ext-s3", setupSql = Some("ATTACH 'y';")),
          None
        )
        .unsafeRunSync()
        .toOption
        .value
      // The response and the stored row both, and the EDIT actually landed: a handler that
      // returned 200 without writing would satisfy the alias assertion alone.
      out.alias shouldBe "ext-s3"
      val sources = fs.listSources(tdId)
      sources.map(_.alias) shouldBe List("ext-s3")
      val row = sources.head
      row.id shouldBe "fs-legacy-hyphen"
      row.setupSql shouldBe "ATTACH 'y';"
    }

  it should "keep the STORED spelling when a legacy invalid alias is re-POSTed in another case" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-legacy-hyphen-2",
          tenantDbId = tdId,
          alias = "Ext-S3",
          setupSql = "ATTACH 'x';"
        )
      )
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "ext-s3", setupSql = Some("ATTACH 'y';")),
        None
      ).unsafeRunSync()
        .isRight shouldBe true
      // The request's spelling must NOT win: letting it would rename the row to a second invalid
      // alias, which is creating one by the back door.
      val sources = fs.listSources(tdId)
      sources.map(_.alias) shouldBe List("Ext-S3")
      sources.head.id shouldBe "fs-legacy-hyphen-2"
    }

  it should "still 400 a hyphenated alias for a `sql` source when no stored row carries it" in
    withEnv { (fs, resolver, _) =>
      // The sibling of the iceberg case above, on the `sql` path this branch changed for
      // everybody: the grandfather clause must not have reopened the rule for new rows.
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      val (code, error) = h
        .createSource(
          "acme",
          "acme_prod",
          FederatedSourceCreateRequest(alias = "ext-s3", setupSql = Some("ATTACH 'x';")),
          None
        )
        .unsafeRunSync()
        .left
        .value
      code shouldBe StatusCode.BadRequest
      error.message should include("ext-s3")
    }

  // --- an omitted readOnly is a declarative reset, not a preserve ---

  it should "reset readOnly to the type default when the upsert omits it" in
    withEnv { (fs, resolver, tdId) =>
      // This endpoint is a create-request-as-upsert, so every omitted field falls back to its
      // default rather than to the stored value. For a read-only `sql` source that default is
      // false, i.e. the catalog is unlocked by an edit that never mentioned readOnly. The
      // behaviour is deliberate (declarative replacement) and is pinned here rather than left to
      // be discovered; the handler WARNs on this exact transition, which is the audit line the
      // manifest path already had for its own version of the same downgrade.
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-ro",
          tenantDbId = tdId,
          alias = "locked",
          setupSql = "ATTACH 'x';",
          readOnly = true
        )
      )
      val out = h
        .createSource(
          "acme",
          "acme_prod",
          FederatedSourceCreateRequest(alias = "locked", setupSql = Some("ATTACH 'y';")),
          None
        )
        .unsafeRunSync()
        .toOption
        .value
      out.readOnly shouldBe false
      fs.listSources(tdId).find(_.alias == "locked").value.readOnly shouldBe false
    }

  it should "keep readOnly when the upsert passes it explicitly" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-ro-2",
          tenantDbId = tdId,
          alias = "locked",
          setupSql = "ATTACH 'x';",
          readOnly = true
        )
      )
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(
          alias = "locked",
          setupSql = Some("ATTACH 'y';"),
          readOnly = Some(true)
        ),
        None
      ).unsafeRunSync()
        .toOption
        .value
        .readOnly shouldBe true
      fs.listSources(tdId).find(_.alias == "locked").value.readOnly shouldBe true
    }

  it should "400 naming the sourceType clash (not the reserved-alias rule) for a legacy row" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-legacy-3",
          tenantDbId = tdId,
          alias = "Sales",
          setupSql = "ATTACH 'x';"
        )
      )
      val (code, err) = h
        .createSource("acme", "acme_prod", iceReq(alias = "sales"), None)
        .unsafeRunSync()
        .left
        .value
      code shouldBe StatusCode.BadRequest
      // Pins the D-D sourceType-clash message specifically, NOT the sibling-reserved-alias
      // message ("alias 'sales' is reserved") that a self-reservation bug would produce here:
      // this row is its own only sibling, so if the reserved set failed to exclude it by id the
      // config-validation arm would 400 first with the wrong rule (or never reach this one).
      err.message should include("already exists as a 'sql' source")
      err.message should include("delete it before creating a 'iceberg_rest' source")
      fs.listSources(tdId).count(_.alias.equalsIgnoreCase("sales")) shouldBe 1
    }

  it should
    "upsert a legacy mixed-case iceberg_rest row in place under its own alias " +
    "(self-reservation by id, not by alias)" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None
      )
      val cfg = IcebergRestConfig(
        uri = "https://catalog.example.com/api/catalog",
        warehouse = "sales",
        authType = Some(IcebergAuthType.OAuth2),
        clientId = Some("{{secret.CID}}"),
        clientSecret = Some("{{secret.CSEC}}")
      )
      fs.upsertSource(
        FederatedSource(
          id = "fs-legacy-4",
          tenantDbId = tdId,
          alias = "Sales_Lake",
          sourceType = FederatedSourceType.IcebergRest,
          config = Some(cfg.toJson),
          readOnly = true
        )
      )
      // Same alias, same case, same sourceType: no D-D clash, so this exercises the
      // config-validation `reserved` set alone. Before the id-based exclusion, this row was
      // NOT excluded from its own reserved set (its stored alias "Sales_Lake" != the normalized
      // "sales_lake"), so `validated` saw its own alias as a collision and 400ed.
      val res = h
        .createSource(
          "acme",
          "acme_prod",
          FederatedSourceCreateRequest(
            alias = "Sales_Lake",
            sourceType = Some("iceberg_rest"),
            config = Some(cfg)
          ),
          None
        )
        .unsafeRunSync()
      res.isRight shouldBe true

      val sources = fs.listSources(tdId)
      sources.count(_.alias.equalsIgnoreCase("sales_lake")) shouldBe 1
      val row = sources.find(_.alias.equalsIgnoreCase("sales_lake")).value
      row.alias shouldBe "sales_lake"
      row.id shouldBe "fs-legacy-4"
    }

  "listSources" should "return the typed config it stored" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod", iceReq(), None).unsafeRunSync()
    val out = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
    out.sources.find(_.alias == "sales_lake").value.config.value.authType shouldBe
      Some(IcebergAuthType.OAuth2)
  }

  // --- Task 8: live attach status surfaced from the registry -----------------

  "listSources" should "surface the attach status supplied by the registry" in
    withEnv { (fs, resolver, tdId) =>
      val h = new FederatedSourceHandlers(
        fedStore = fs,
        resolver = resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None,
        attachStatusOf = (tenantDbId, alias) =>
          if tenantDbId == tdId && alias == "sales_lake" then Some("failed on 1 of 2 nodes")
          else None
      )
      h.createSource("acme", "acme_prod", iceReq(), None).unsafeRunSync()
      val out = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
      out.sources.find(_.alias == "sales_lake").value.attachStatus shouldBe
        Some("failed on 1 of 2 nodes")
    }

  it should "leave attachStatus unset for a sql source and for a disabled iceberg one" in
    withEnv { (fs, resolver, tdId) =>
      // The registry answers for EVERY alias it is asked about, so if the handler asked, the
      // assertions below would read Some("unknown") instead of None.
      val h = new FederatedSourceHandlers(
        fedStore = fs,
        resolver = resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => None,
        attachStatusOf = (tenantDbId, _) => if tenantDbId == tdId then Some("unknown") else None
      )
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "pg_src", setupSql = Some("ATTACH 'x';")),
        None
      ).unsafeRunSync()
      h.createSource(
        "acme",
        "acme_prod",
        iceReq(alias = "off_lake").copy(disabled = true),
        None
      ).unsafeRunSync()
      h.createSource("acme", "acme_prod", iceReq(), None).unsafeRunSync()

      val out = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
      out.sources.find(_.alias == "pg_src").value.attachStatus shouldBe None
      out.sources.find(_.alias == "off_lake").value.attachStatus shouldBe None
      // The enabled iceberg row in the same response still reports, so the gate is on the row's
      // own type/disabled state and not on the whole call.
      out.sources.find(_.alias == "sales_lake").value.attachStatus shouldBe Some("unknown")
    }

  // --- postgres / mysql typed sources ---------------------------------------

  private def pgReq(
      alias: String = "shop_pg",
      sourceType: String = "postgres",
      conn: ai.starlake.quack.ondemand.federation.rdbms.RdbmsConfig =
        ai.starlake.quack.ondemand.federation.rdbms.RdbmsConfig(
          host = "pg.example.com",
          database = "shop",
          user = "reader",
          password = "{{secret.PG_PWD}}"
        ),
      readOnly: Option[Boolean] = None
  ) = FederatedSourceCreateRequest(
    alias = alias,
    sourceType = Some(sourceType),
    connection = Some(conn),
    readOnly = readOnly
  )

  "createSource (postgres / mysql)" should "accept a typed source, default it read-only and echo the connection" in
    withHandlers { (h, _) =>
      for (t, alias) <- List("postgres" -> "shop_pg", "mysql" -> "shop_my") do
        val out = h
          .createSource("acme", "acme_prod", pgReq(alias = alias, sourceType = t), None)
          .unsafeRunSync()
          .toOption
          .value
        out.sourceType shouldBe t
        out.readOnly shouldBe true
        out.connection.value.host shouldBe "pg.example.com"
        out.connection.value.password shouldBe "{{secret.PG_PWD}}"
        // The Iceberg field stays empty: a connection JSON would otherwise decode as an all-default
        // IcebergRestConfig.
        out.config shouldBe None
        out.setupSql shouldBe None
        h.getSource("acme", "acme_prod", alias)
          .unsafeRunSync()
          .toOption
          .value
          .connection
          .value
          .database shouldBe "shop"
    }

  it should "honour an explicit readOnly=false" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod", pgReq(readOnly = Some(false)), None)
      .unsafeRunSync()
      .toOption
      .value
      .readOnly shouldBe false
  }

  it should "400 on a missing connection, naming the wire field" in withHandlers { (h, _) =>
    val (code, err) = h
      .createSource("acme", "acme_prod", pgReq().copy(connection = None), None)
      .unsafeRunSync()
      .left
      .value
    code shouldBe StatusCode.BadRequest
    err.message should include("connection is required for sourceType 'postgres'")
  }

  it should "400 on a connection sent for another type, and on config or setupSql sent for postgres" in
    withHandlers { (h, _) =>
      val onSql = FederatedSourceCreateRequest(
        alias = "x",
        setupSql = Some("ATTACH 'x';"),
        connection = pgReq().connection
      )
      h.createSource("acme", "acme_prod", onSql, None).unsafeRunSync().left.value._2.message should
        include("connection is for sourceType 'postgres' / 'mysql'")
      val withIce = pgReq().copy(config = Some(IcebergRestConfig(warehouse = "w")))
      h.createSource("acme", "acme_prod", withIce, None)
        .unsafeRunSync()
        .left
        .value
        ._2
        .message should
        include("use connection")
      val withSql = pgReq().copy(setupSql = Some("ATTACH 'x';"))
      h.createSource("acme", "acme_prod", withSql, None)
        .unsafeRunSync()
        .left
        .value
        ._2
        .message should
        include("setupSql is for sourceType 'sql'")
    }

  it should "400 on a literal password without echoing it" in withHandlers { (h, _) =>
    val req         = pgReq(conn = pgReq().connection.value.copy(password = "hunter2"))
    val (code, err) = h.createSource("acme", "acme_prod", req, None).unsafeRunSync().left.value
    code shouldBe StatusCode.BadRequest
    err.message should include("password must be a secret placeholder")
    err.message should not include "hunter2"
  }

  it should "400 on a socket-path host and on a postgres-only field for mysql" in withHandlers {
    (h, _) =>
      val sock = pgReq(conn = pgReq().connection.value.copy(host = "/var/run/postgresql"))
      h.createSource("acme", "acme_prod", sock, None).unsafeRunSync().left.value._2.message should
        include("socket path is not accepted")
      val my = pgReq(sourceType = "mysql", conn = pgReq().connection.value.copy(schema = Some("s")))
      h.createSource("acme", "acme_prod", my, None).unsafeRunSync().left.value._2.message should
        include("schema applies only to sourceType 'postgres'")
  }

  it should "400 on an alias that collides with a sibling or the tenant-db's own alias" in
    withEnv { (fs, resolver, _) =>
      val h = new FederatedSourceHandlers(
        fs,
        resolver,
        scopeOf = _ => SessionScope.Superuser,
        invalidateSources = _ => (),
        publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
        catalogAliasOf = _ => Some("acme_db")
      )
      h.createSource("acme", "acme_prod", pgReq(alias = "acme_db"), None)
        .unsafeRunSync()
        .left
        .value
        ._2
        .message should include("reserved")
      h.createSource("acme", "acme_prod", iceReq(alias = "lake"), None).unsafeRunSync()
      h.createSource("acme", "acme_prod", pgReq(alias = "lake"), None)
        .unsafeRunSync()
        .left
        .value
        ._2
        .message should include("already exists as a 'iceberg_rest' source")
    }

  it should "upsert a same-type re-POST in place, keeping one row" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod", pgReq(), None).unsafeRunSync()
    val edited = pgReq(conn = pgReq().connection.value.copy(port = Some(6432)))
    h.createSource("acme", "acme_prod", edited, None).unsafeRunSync().isRight shouldBe true
    val rows = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value.sources
    rows.count(_.alias == "shop_pg") shouldBe 1
    rows.find(_.alias == "shop_pg").value.connection.value.port shouldBe Some(6432)
  }

  it should "report attachStatus for an enabled postgres row" in withEnv { (fs, resolver, tdId) =>
    val h = new FederatedSourceHandlers(
      fedStore = fs,
      resolver = resolver,
      scopeOf = _ => SessionScope.Superuser,
      invalidateSources = _ => (),
      publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
      catalogAliasOf = _ => None,
      attachStatusOf = (tenantDbId, _) => if tenantDbId == tdId then Some("unknown") else None
    )
    h.createSource("acme", "acme_prod", pgReq(), None).unsafeRunSync()
    h.listSources("acme", "acme_prod")
      .unsafeRunSync()
      .toOption
      .value
      .sources
      .find(_.alias == "shop_pg")
      .value
      .attachStatus shouldBe Some("unknown")
  }

  it should "400 a sql source whose setupSql names a secret prefix a typed source uses" in
    withHandlers { (h, _) =>
      val req = FederatedSourceCreateRequest(
        alias = "legacy",
        setupSql = Some("CREATE SECRET QOD_PG_shop (TYPE postgres);")
      )
      h.createSource("acme", "acme_prod", req, None).unsafeRunSync().left.value._2.message should
        include("reserved for QoD's typed sources")
    }

  it should "judge the reserved secret prefix after {{alias}} expansion" in withHandlers { (h, _) =>
    val req = FederatedSourceCreateRequest(
      alias = "pg_x",
      setupSql = Some("CREATE SECRET qod_{{alias}} (TYPE postgres);")
    )
    h.createSource("acme", "acme_prod", req, None).unsafeRunSync().left.value._2.message should
      include("reserved for QoD's typed sources")
  }

  // CONTRACT Addendum 4 item 26 (Task 20 review C1): a sql source's setupSql runs unrestricted on
  // every node, so only a superuser creates or changes one; a typed source stays a tenant admin's.
  "a sql federated source" should "be refused to a tenant admin on create and on change (403)" in
    withEnv { (fs, resolver, tdId) =>
      val h =
        new FederatedSourceHandlers(
          fs,
          resolver,
          scopeOf = scopes,
          invalidateSources = _ => (),
          publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
          catalogAliasOf = _ => None
        )
      val sql = FederatedSourceCreateRequest(
        alias = "leak",
        setupSql = Some("CREATE SECURE VIEW v AS SELECT * FROM read_text('/etc/passwd');")
      )
      val created = h.createSource("acme", "acme_prod", sql, Some(AdminTok)).unsafeRunSync()
      created.left.value._1 shouldBe StatusCode.Forbidden
      created.left.value._2.error shouldBe "superuser_required"
      // An explicit sourceType 'sql' is the same.
      h.createSource("acme", "acme_prod", sql.copy(sourceType = Some(" SQL ")), Some(AdminTok))
        .unsafeRunSync()
        .left
        .value
        ._2
        .error shouldBe "superuser_required"
      fs.getSource(tdId, "leak") shouldBe None
      // A superuser's is stored, with a known author; a tenant admin's change of it is refused.
      h.createSource(
        "acme",
        "acme_prod",
        sql.copy(setupSql = Some("INSTALL postgres;")),
        Some(SuperTok)
      ).unsafeRunSync()
        .isRight shouldBe true
      fs.getSource(tdId, "leak").value.setupSqlTrusted shouldBe true
      h.createSource("acme", "acme_prod", sql.copy(disabled = true), Some(AdminTok))
        .unsafeRunSync()
        .left
        .value
        ._2
        .error shouldBe "superuser_required"
      fs.getSource(tdId, "leak").value.setupSql shouldBe "INSTALL postgres;"
      // A typed source is still a tenant admin's to write.
      h.createSource("acme", "acme_prod", pgReq(), Some(AdminTok)).unsafeRunSync().isRight shouldBe
        true
    }

  // --- a source's connection, and the secrets stored for it ------------------

  /** A superuser's handler and a tenant admin's, over one store. */
  private def bothHandlers(
      fs: FederatedSourceStore,
      resolver: (String, String) => Option[String],
      audit: ai.starlake.quack.ondemand.telemetry.AuditRecorder =
        ai.starlake.quack.ondemand.telemetry.AuditRecorder.noop
  ) =
    def handlers(scope: SessionScope.Resolver) = new FederatedSourceHandlers(
      fs,
      resolver,
      audit = audit,
      scopeOf = scope,
      invalidateSources = _ => (),
      publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
      catalogAliasOf = _ => None
    )
    (handlers(_ => SessionScope.Superuser), handlers(tenantAdminScope))

  private def create(h: FederatedSourceHandlers, req: FederatedSourceCreateRequest) =
    h.createSource("acme", "acme_prod", req, None).unsafeRunSync()

  private def withSecret(h: FederatedSourceHandlers, alias: String, name: String) =
    h.upsertSecret(
      "acme",
      "acme_prod",
      alias,
      FederatedSecretUpsertRequest(name = name, value = Some("stored-secret-sentinel")),
      None
    ).unsafeRunSync()
      .isRight shouldBe true

  private val StoredConn = ai.starlake.quack.ondemand.federation.rdbms.RdbmsConfig(
    host = "pg.example.com",
    port = Some(5432),
    database = "shop",
    user = "reader",
    password = "{{secret.PG_PWD}}",
    schema = Some("sales"),
    sslMode = Some("require")
  )

  "A source holding a secret" should "take no other connection from a tenant admin" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      for t <- List("postgres", "mysql") do
        val alias  = s"held_$t"
        val stored =
          if t == "postgres" then StoredConn else StoredConn.copy(schema = None, sslMode = None)
        create(su, pgReq(alias, t, stored)).isRight shouldBe true
        withSecret(su, alias, "PG_PWD")
        val before = fs.getSource(tdId, alias).value
        List(
          stored.copy(host = "elsewhere.example.com"),
          stored.copy(port = Some(5433)),
          stored.copy(database = "other"),
          stored.copy(user = "another"),
          stored.copy(password = "{{secret.OTHER}}"),
          stored.copy(sslMode = Some(if t == "postgres" then "disable" else "disabled"))
        ).foreach { moved =>
          withClue(moved) {
            val (status, err) = create(admin, pgReq(alias, t, moved)).left.value
            status shouldBe StatusCode.Forbidden
            err.error shouldBe "superuser_required"
            err.message should include("a superuser changes it")
            err.message should include("delete the source and create it again")
            err.message should not include "pg.example.com"
            err.message should not include "stored-secret-sentinel"
            fs.getSource(tdId, alias).value shouldBe before
          }
        }
        // A superuser's is not judged.
        create(su, pgReq(alias, t, stored.copy(host = "new.example.com"))).isRight shouldBe true
        fs.getSource(tdId, alias).value.config.value should include("new.example.com")
    }

  it should "take no other config from a tenant admin" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      val stored      = iceReq("held_ice").config.value
      create(su, iceReq("held_ice")).isRight shouldBe true
      withSecret(su, "held_ice", "CSEC")
      val before = fs.getSource(tdId, "held_ice").value
      List(
        stored.copy(uri = "https://elsewhere.example.com/api"),
        stored.copy(warehouse = "other"),
        stored.copy(clientId = Some("{{secret.OTHER}}")),
        stored.copy(clientSecret = Some("{{secret.OTHER}}")),
        stored.copy(oauth2ServerUri = Some("https://elsewhere.example.com/token")),
        stored.copy(oauth2Scope = Some("PRINCIPAL_ROLE:ALL"))
      ).foreach { moved =>
        withClue(moved) {
          val (status, err) = create(admin, iceReq("held_ice", moved)).left.value
          status shouldBe StatusCode.Forbidden
          err.error shouldBe "superuser_required"
          fs.getSource(tdId, "held_ice").value shouldBe before
        }
      }
    }

  it should "read the same values sent again as no change, however they are spelled" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      val bare        = StoredConn.copy(port = None, schema = None, sslMode = None)
      create(su, pgReq("spelled", "postgres", bare)).isRight shouldBe true
      withSecret(su, "spelled", "PG_PWD")
      List(
        bare,
        // The default port, written out: what a form sends for a row that left it out.
        bare.copy(port = Some(5432)),
        // Blank optionals and surrounding spaces render as nothing.
        bare.copy(schema = Some(""), sslMode = Some(" ")),
        bare.copy(host = " pg.example.com ", database = "shop ", user = " reader")
      ).foreach { same =>
        withClue(same) {
          create(admin, pgReq("spelled", "postgres", same)).isRight shouldBe true
        }
      }
      // Stored by an earlier writer: nulls, absent fields and another key order.
      fs.upsertSource(
        fs.getSource(tdId, "spelled")
          .value
          .copy(config =
            Some(
              """{"user":"reader","password":"{{secret.PG_PWD}}","schema":null,"host":"pg.example.com","database":"shop"}"""
            )
          )
      )
      create(admin, pgReq("spelled", "postgres", bare)).isRight shouldBe true

      create(su, iceReq("spelled_ice")).isRight shouldBe true
      withSecret(su, "spelled_ice", "CSEC")
      val stored = iceReq("spelled_ice").config.value
      create(admin, iceReq("spelled_ice", stored)).isRight shouldBe true
      // Blank optionals and an empty list, as a form may send them.
      create(
        admin,
        iceReq("spelled_ice", stored.copy(oauth2Scope = Some(" "), awsScope = Nil))
      ).isRight shouldBe true
    }

  it should "stay open to a tenant admin for its other fields" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      create(su, pgReq("others", "postgres", StoredConn)).isRight shouldBe true
      withSecret(su, "others", "PG_PWD")
      val same = pgReq("others", "postgres", StoredConn)
      create(admin, same.copy(description = Some("described"))).isRight shouldBe true
      fs.getSource(tdId, "others").value.description shouldBe Some("described")
      create(admin, same.copy(disabled = true)).isRight shouldBe true
      fs.getSource(tdId, "others").value.disabled shouldBe true
      fs.getSource(tdId, "others").value.config.value should include("pg.example.com")
    }

  "A read-only source holding a secret" should "stay read-only for a tenant admin" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      List(
        pgReq("ro_pg", "postgres", StoredConn),
        pgReq("ro_my", "mysql", StoredConn.copy(schema = None, sslMode = None)),
        iceReq("ro_ice")
      ).foreach { req =>
        withClue(req.alias) {
          create(su, req).isRight shouldBe true
          withSecret(su, req.alias, "PG_PWD")
          val before = fs.getSource(tdId, req.alias).value
          before.readOnly shouldBe true
          val (status, err) = create(admin, req.copy(readOnly = Some(false))).left.value
          status shouldBe StatusCode.Forbidden
          err.error shouldBe "superuser_required"
          err.message should include("a superuser changes it")
          err.message should include("delete the source and create it again")
          fs.getSource(tdId, req.alias).value shouldBe before
          // Whatever else the write holds.
          create(
            admin,
            req.copy(readOnly = Some(false), description = Some("described"), disabled = true)
          ).left.value._2.error shouldBe "superuser_required"
          fs.getSource(tdId, req.alias).value shouldBe before
          // With nothing said of it, a typed source's default keeps it read-only.
          create(admin, req.copy(description = Some("described"))).isRight shouldBe true
          fs.getSource(tdId, req.alias).value.readOnly shouldBe true
          // Said again, it stays.
          create(admin, req.copy(readOnly = Some(true))).isRight shouldBe true
          fs.getSource(tdId, req.alias).value.readOnly shouldBe true
        }
      }
      // A request breaking both rules gets one refusal.
      val moved = pgReq("ro_pg", "postgres", StoredConn.copy(host = "elsewhere.example.com"))
      val both  = create(admin, moved.copy(readOnly = Some(false))).left.value
      both._1 shouldBe StatusCode.Forbidden
      both._2.error shouldBe "superuser_required"
      // A read-only sql source's default is writable, and a tenant admin writes no sql source.
      fs.upsertSource(
        FederatedSource(
          id = "fs-ro-sql",
          tenantDbId = tdId,
          alias = "ro_sql",
          setupSql = "ATTACH 'x';",
          readOnly = true
        )
      )
      withSecret(su, "ro_sql", "PG_PWD")
      val sql = FederatedSourceCreateRequest(alias = "ro_sql", setupSql = Some("ATTACH 'x';"))
      create(admin, sql).left.value._2.error shouldBe "superuser_required"
      fs.getSource(tdId, "ro_sql").value.readOnly shouldBe true
      // A superuser may.
      create(
        su,
        pgReq("ro_pg", "postgres", StoredConn).copy(readOnly = Some(false))
      ).isRight shouldBe
        true
      fs.getSource(tdId, "ro_pg").value.readOnly shouldBe false
    }

  it should "leave open what ends no read-only source writable" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      // A source holding no secret.
      create(su, pgReq("no_secret", "postgres", StoredConn)).isRight shouldBe true
      create(
        admin,
        pgReq("no_secret", "postgres", StoredConn).copy(readOnly = Some(false))
      ).isRight shouldBe
        true
      fs.getSource(tdId, "no_secret").value.readOnly shouldBe false
      // A create of a new source.
      create(
        admin,
        pgReq("fresh", "postgres", StoredConn).copy(readOnly = Some(false))
      ).isRight shouldBe
        true
      fs.getSource(tdId, "fresh").value.readOnly shouldBe false
      // A writable source holding a secret: it stays so when said again, and may turn read-only.
      val writable = pgReq("writable", "postgres", StoredConn).copy(readOnly = Some(false))
      create(su, writable).isRight shouldBe true
      withSecret(su, "writable", "PG_PWD")
      create(admin, writable).isRight shouldBe true
      fs.getSource(tdId, "writable").value.readOnly shouldBe false
      create(admin, writable.copy(readOnly = Some(true))).isRight shouldBe true
      fs.getSource(tdId, "writable").value.readOnly shouldBe true
      // And then it stays read-only.
      create(admin, writable).left.value._2.error shouldBe "superuser_required"
      // A delete and a recreate, which brings no secret along.
      admin
        .deleteSource("acme", "acme_prod", "writable", None)
        .unsafeRunSync()
        .isRight shouldBe true
      create(admin, writable).isRight shouldBe true
      val row = fs.getSource(tdId, "writable").value
      row.readOnly shouldBe false
      fs.listSecrets(row.id) shouldBe empty
    }

  it should "be audited as denied when a tenant admin's write would leave it writable" in
    withEnv { (fs, resolver, _) =>
      val events      = new ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
      val recorder    = new ai.starlake.quack.ondemand.telemetry.AuditRecorder(events, _ => None)
      val (su, admin) = bothHandlers(fs, resolver, recorder)
      create(su, pgReq("audited", "postgres", StoredConn)).isRight shouldBe true
      withSecret(su, "audited", "PG_PWD")
      events.events.clear()
      create(
        admin,
        pgReq("audited", "postgres", StoredConn).copy(readOnly = Some(false))
      ).isLeft shouldBe
        true
      val denied = events.events.toList.filter(_.outcome == "denied")
      denied.map(_.action) shouldBe
        List(ai.starlake.quack.ondemand.telemetry.AuditActions.FederationSourceUpsert)
      denied.head.target shouldBe Some("audited")
    }

  "A write leaving a read-only source writable" should "log its line, whoever makes it" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      val logger      = classOf[FederatedSourceHandlers].getName
      def writable(events: List[ch.qos.logback.classic.spi.ILoggingEvent]) =
        events.filter(_.getFormattedMessage.contains("WRITABLE"))
      val req = pgReq("warned", "postgres", StoredConn)
      create(su, req).isRight shouldBe true
      withSecret(su, "warned", "PG_PWD")
      // A tenant admin's is refused, so nothing became writable.
      val (_, refused) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture.logged(logger) {
        create(admin, req.copy(readOnly = Some(false))).isLeft shouldBe true
      }
      writable(refused) shouldBe empty
      // A superuser's explicit false.
      val (_, explicit) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture.logged(logger) {
        create(su, req.copy(readOnly = Some(false))).isRight shouldBe true
      }
      val line = writable(explicit)
      line should have size 1
      line.head.getLevel shouldBe ch.qos.logback.classic.Level.WARN
      line.head.getFormattedMessage should include("readOnly=false")
      // False again, and false to true, log nothing.
      val (_, again) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture.logged(logger) {
        create(su, req.copy(readOnly = Some(false))).isRight shouldBe true
        create(su, req.copy(readOnly = Some(true))).isRight shouldBe true
      }
      writable(again) shouldBe empty
      // A sql source's omitted field, which its default makes writable.
      fs.upsertSource(
        FederatedSource(
          id = "fs-warn-sql",
          tenantDbId = tdId,
          alias = "warned_sql",
          setupSql = "ATTACH 'x';",
          readOnly = true
        )
      )
      val (_, omitted) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture.logged(logger) {
        create(
          su,
          FederatedSourceCreateRequest(alias = "warned_sql", setupSql = Some("ATTACH 'x';"))
        ).isRight shouldBe true
      }
      val omittedLine = writable(omitted)
      omittedLine should have size 1
      omittedLine.head.getFormattedMessage should include("omitted readOnly")
    }

  it should "take another connection once a tenant admin has deleted and recreated it" in
    withEnv { (fs, resolver, tdId) =>
      val (su, admin) = bothHandlers(fs, resolver)
      create(su, pgReq("again", "postgres", StoredConn)).isRight shouldBe true
      withSecret(su, "again", "PG_PWD")
      admin.deleteSource("acme", "acme_prod", "again", None).unsafeRunSync().isRight shouldBe true
      val moved = StoredConn.copy(host = "elsewhere.example.com")
      create(admin, pgReq("again", "postgres", moved)).isRight shouldBe true
      // Nothing stored came along.
      val row = fs.getSource(tdId, "again").value
      row.config.value should include("elsewhere.example.com")
      fs.listSecrets(row.id) shouldBe empty
      // Holding none, it takes another connection; holding its own again, it does not.
      create(
        admin,
        pgReq("again", "postgres", moved.copy(host = "third.example.com"))
      ).isRight shouldBe true
      withSecret(admin, "again", "PG_PWD")
      create(admin, pgReq("again", "postgres", moved)).left.value._2.error shouldBe
        "superuser_required"
    }

  it should "be audited as denied when refused, with nothing of its connection" in
    withEnv { (fs, resolver, _) =>
      val events      = new ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
      val recorder    = new ai.starlake.quack.ondemand.telemetry.AuditRecorder(events, _ => None)
      val (su, admin) = bothHandlers(fs, resolver, recorder)
      create(su, pgReq("audited", "postgres", StoredConn)).isRight shouldBe true
      withSecret(su, "audited", "PG_PWD")
      events.events.clear()
      val moved = StoredConn.copy(host = "elsewhere.example.com")
      create(admin, pgReq("audited", "postgres", moved)).isLeft shouldBe true
      val denied = events.events.toList.filter(_.outcome == "denied")
      denied.map(_.action) shouldBe
        List(ai.starlake.quack.ondemand.telemetry.AuditActions.FederationSourceUpsert)
      denied.head.target shouldBe Some("audited")
      denied.head.toString should not include "elsewhere.example.com"
    }

  "the boot report" should "name each sql source with no known author, never its SQL" in
    withEnv { (fs, _, tdId) =>
      fs.upsertSource(
        FederatedSource(
          id = "fs-old",
          tenantDbId = tdId,
          alias = "legacy",
          setupSql = "ATTACH 'host=h password=AKIAQODFEDMARKER' AS legacy (TYPE postgres);"
        )
      )
      fs.upsertSource(
        FederatedSource(id = "fs-new", tenantDbId = tdId, alias = "vouched", setupSql = "SELECT 1;")
          .copy(setupSqlTrusted = true)
      )
      val td = TenantDb(
        id = tdId,
        tenantId = "acme",
        name = "acme_prod",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = ""
      )
      val lines = ai.starlake.quack.ondemand.initsql.InitSqlTrustReport
        .federationLines(fs.listSources(tdId).map(td -> _))
      lines should have size 1
      lines.head should include("tenant 'acme' database 'acme_prod' federated source 'legacy'")
      lines.head should not include "AKIAQODFEDMARKER"
    }

  // CONTRACT Addendum 4 item 29 (amended): a secret placeholder of a sql source sits inside a
  // single-quoted literal, or the save is refused, naming the placeholder (whoever saves).
  "a sql source" should "be refused at save with a secret placeholder outside a literal" in
    withEnv { (fs, resolver, tdId) =>
      val h =
        new FederatedSourceHandlers(
          fs,
          resolver,
          scopeOf = scopes,
          invalidateSources = _ => (),
          publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
          catalogAliasOf = _ => None
        )
      val req = FederatedSourceCreateRequest(
        alias = "legacy",
        setupSql = Some("ATTACH 'host=h' AS {{alias}} (TYPE postgres, PORT {{secret.PORT}});")
      )
      val out = h.createSource("acme", "acme_prod", req, Some(SuperTok)).unsafeRunSync()
      out.left.value._1 shouldBe StatusCode.BadRequest
      out.left.value._2.message should include("{{secret.PORT}} outside a single-quoted literal")
      fs.getSource(tdId, "legacy") shouldBe None
      h.createSource(
        "acme",
        "acme_prod",
        req.copy(setupSql =
          Some("ATTACH 'host=h port={{secret.PORT}}' AS {{alias}} (TYPE postgres);")
        ),
        Some(SuperTok)
      ).unsafeRunSync()
        .isRight shouldBe true
    }

  // ---------- what the manager prints about an Iceberg source ----------

  /** What `body` prints on stdout about an Iceberg source. */
  private def printedBy[A](body: => A): (A, List[String]) =
    val out      = new java.io.ByteArrayOutputStream()
    val previous = System.out
    System.setOut(new java.io.PrintStream(out, true, "UTF-8"))
    try
      val a = body
      (a, out.toString("UTF-8").linesIterator.filter(_.startsWith("WARN: Iceberg")).toList)
    finally System.setOut(previous)

  private def lineOf(alias: String) =
    s"WARN: Iceberg source acme/acme_prod/$alias: a node reads whatever files its catalog lists, " +
      "local files included. In production set QOD_NODE_ALLOW_LOCAL_FILES=false, with data on " +
      "object storage."

  private val tenantAdminScope: SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set("acme"))

  private def asTenantAdmin(
      fs: FederatedSourceStore,
      resolver: (String, String) => Option[String]
  ) =
    new FederatedSourceHandlers(
      fs,
      resolver,
      scopeOf = tenantAdminScope,
      invalidateSources = _ => (),
      publish = ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
      catalogAliasOf = _ => None
    )

  "An iceberg_rest source" should "print one line when created and one when changed" in
    withHandlers { (h, _) =>
      val (created, first) =
        printedBy(h.createSource("acme", "acme_prod", iceReq("lake_a"), None).unsafeRunSync())
      created.isRight shouldBe true
      first shouldBe List(lineOf("lake_a"))
      val (changed, second) = printedBy(
        h.createSource(
          "acme",
          "acme_prod",
          iceReq("lake_a").copy(description = Some("the lake")),
          None
        ).unsafeRunSync()
      )
      changed.isRight shouldBe true
      second shouldBe List(lineOf("lake_a"))
    }

  it should "print for a tenant admin, whose write it does not refuse" in
    withEnv { (fs, resolver, _) =>
      val h                = asTenantAdmin(fs, resolver)
      val (created, first) =
        printedBy(h.createSource("acme", "acme_prod", iceReq("lake_b"), None).unsafeRunSync())
      withClue(created)(created.isRight shouldBe true)
      first shouldBe List(lineOf("lake_b"))
      val (changed, second) = printedBy(
        h.createSource(
          "acme",
          "acme_prod",
          iceReq("lake_b", readOnly = Some(false)),
          None
        ).unsafeRunSync()
      )
      changed.isRight shouldBe true
      second shouldBe List(lineOf("lake_b"))
    }

  it should "print for a disabled source, and name it as it is stored" in withHandlers { (h, _) =>
    val (_, lines) = printedBy(
      h.createSource(
        "acme",
        "acme_prod",
        iceReq("Lake_C").copy(disabled = true),
        None
      ).unsafeRunSync()
    )
    lines shouldBe List(lineOf("lake_c"))
  }

  it should "print nothing for a write it refuses" in withHandlers { (h, _) =>
    val (out, lines) =
      printedBy(h.createSource("acme", "acme_prod", iceReq("not-an-alias"), None).unsafeRunSync())
    out.isLeft shouldBe true
    lines shouldBe Nil
  }

  it should "say nothing of its connection" in withHandlers { (h, _) =>
    val cfg = IcebergRestConfig(
      uri = "https://catalog.example.com/api/catalog",
      warehouse = "wh_sentinel",
      authType = Some(IcebergAuthType.OAuth2),
      clientId = Some("client_sentinel"),
      clientSecret = Some("{{secret.CSEC}}")
    )
    val (_, lines) = printedBy(
      h.createSource(
        "acme",
        "acme_prod",
        iceReq("lake_d", cfg = cfg).copy(description = Some("desc_sentinel")),
        None
      ).unsafeRunSync()
    )
    lines should have size 1
    List(
      "catalog.example.com",
      "wh_sentinel",
      "client_sentinel",
      "CSEC",
      "secret.",
      "desc_sentinel"
    )
      .foreach(lines.head should not include _)
  }

  "A source of another type" should "print nothing, on create or change" in withHandlers { (h, _) =>
    val requests = List(
      pgReq("shop_pg"),
      pgReq("shop_my", sourceType = "mysql"),
      FederatedSourceCreateRequest(alias = "fedsql", setupSql = Some("INSTALL postgres;"))
    )
    val (out, lines) = printedBy(
      requests.flatMap(r =>
        List(
          h.createSource("acme", "acme_prod", r, None).unsafeRunSync(),
          h.createSource("acme", "acme_prod", r, None).unsafeRunSync()
        )
      )
    )
    out.foreach(r => withClue(r)(r.isRight shouldBe true))
    lines shouldBe Nil
  }

  "BootPreflight.warnIcebergSources" should
    "print one line per stored iceberg_rest source, disabled or not, and none for another type" in
    withEnv { (fs, _, tdId) =>
      def source(alias: String, kind: FederatedSourceType, disabled: Boolean = false) =
        fs.upsertSource(
          FederatedSource(
            id = s"fs-$alias",
            tenantDbId = tdId,
            alias = alias,
            setupSql = "",
            disabled = disabled,
            sourceType = kind,
            config = Some("""{"uri":"https://catalog.example.com","token":"sentinel"}""")
          )
        )
      source("lake_a", FederatedSourceType.IcebergRest)
      source("lake_b", FederatedSourceType.IcebergRest, disabled = true)
      source("shop_pg", FederatedSourceType.Postgres)
      source("shop_my", FederatedSourceType.Mysql)
      source("fedsql", FederatedSourceType.Sql)
      val (_, lines) = printedBy(
        ai.starlake.quack.boot.BootPreflight
          .warnIcebergSources(fs.sourceNames(FederatedSourceType.IcebergRest))
      )
      lines shouldBe List(lineOf("lake_a"), lineOf("lake_b"))
      lines.foreach { l =>
        l should not include "catalog.example.com"
        l should not include "sentinel"
      }
    }

  it should "never fail a boot" in {
    val (_, lines) =
      printedBy(
        ai.starlake.quack.boot.BootPreflight.warnIcebergSources(throw new RuntimeException("boom"))
      )
    lines shouldBe Nil
  }

  "IcebergSourceWarning.line" should "stay one line, whatever a stored name holds" in {
    val line = ai.starlake.quack.ondemand.federation.iceberg.IcebergSourceWarning
      .line("acme", "acme_prod", "old\nWARN: forged")
    line.linesIterator.size shouldBe 1
    line should startWith("WARN: Iceberg source acme/acme_prod/old\\u000aWARN: forged: ")
  }

  private val chainConfig = IcebergRestConfig(
    uri = "https://glue.example.com/iceberg",
    warehouse = "123456789012",
    authType = Some(IcebergAuthType.SigV4),
    awsCredentials = Some(ai.starlake.quack.ondemand.federation.iceberg.AwsCredentials.CredentialChain),
    awsRegion = Some("eu-west-1"),
    awsScope = List("s3://lake/sales/")
  )

  "An Iceberg source using the credential chain" should "be refused to a tenant admin, nothing stored" in
    withEnv { (fs, resolver, tdId) =>
      val (_, admin) = bothHandlers(fs, resolver)
      create(admin, iceReq("chain_ice", chainConfig)).left.value._2.error shouldBe "superuser_required"
      fs.getSource(tdId, "chain_ice") shouldBe None
    }

  it should "be accepted from a superuser" in
    withEnv { (fs, resolver, tdId) =>
      val (su, _) = bothHandlers(fs, resolver)
      create(su, iceReq("chain_ice", chainConfig)).isRight shouldBe true
      fs.getSource(tdId, "chain_ice").isDefined shouldBe true
    }

  it should "stay editable by a tenant admin only when sent back unchanged" in
    withEnv { (fs, resolver, _) =>
      val (su, admin) = bothHandlers(fs, resolver)
      create(su, iceReq("chain_ice", chainConfig)).isRight shouldBe true
      create(admin, iceReq("chain_ice", chainConfig)).isRight shouldBe true
      val moved = chainConfig.copy(awsScope = List("s3://lake/other/"))
      create(admin, iceReq("chain_ice", moved)).left.value._2.error shouldBe "superuser_required"
    }
