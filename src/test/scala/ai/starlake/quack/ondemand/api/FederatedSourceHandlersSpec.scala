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
    val h = new FederatedSourceHandlers(fs, resolver, scopeOf = scopeOf, catalogAliasOf = _ => None)
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
        new FederatedSourceHandlers(fs, resolver, scopeOf = scopes, catalogAliasOf = _ => None)
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
        new FederatedSourceHandlers(fs, resolver, scopeOf = scopes, catalogAliasOf = _ => None)
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
