package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.edge.authz.location.LocationRules
import ai.starlake.quack.edge.cls.{ColumnCatalog, SchemaContext}
import ai.starlake.quack.edge.policy.{DottedIdentifierScreen, GuardOutcome, ProtectedWriteGuard}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RolePermission, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Task 12: the gateway admits a file reader call or a lone COPY whose every location the tenant
  * authorized, and refuses the nearest thing it must still refuse. The admission here is a plain
  * predicate standing in for the tenant's LocationChecker rules (one case uses the real checker).
  */
class GatewayLocationAdmissionSpec extends AnyFlatSpec with Matchers:

  private val Authorized = List("s3://acme-etl/", "/srv/qod/acme/in/", "/srv/qod/acme/out/")
  private val admission  = FileAdmission(loc => Authorized.exists(loc.startsWith))

  private val admittedForms = List(
    "SELECT * FROM read_parquet('s3://acme-etl/in/x.parquet')",
    "SELECT * FROM READ_CSV('/srv/qod/acme/in/x.csv', header = true)",
    "SELECT * FROM \"read_json\"('s3://acme-etl/in/x.json')",
    "SELECT * FROM read_parquet(['s3://acme-etl/a.parquet', 's3://acme-etl/b.parquet'])",
    "SELECT * FROM glob('/srv/qod/acme/in/*.csv')",
    "SELECT count(*) FROM read_parquet('s3://acme-etl/in/*.parquet') r JOIN " +
      "read_csv('s3://acme-etl/in/y.csv') c ON true",
    "COPY lake.main.events FROM 's3://acme-etl/in/e.csv'",
    "COPY lake.main.orders TO 's3://acme-etl/out/o.parquet' (FORMAT parquet)",
    "COPY (SELECT id FROM lake.main.orders) TO '/srv/qod/acme/out/o.csv' WITH (HEADER)"
  )

  private val stillRefused = List(
    // the same shapes, one location not authorized
    "SELECT * FROM read_parquet('s3://other-bucket/x.parquet')",
    "SELECT * FROM read_csv('/etc/hosts')",
    "SELECT * FROM read_csv('x.csv')",
    "SELECT * FROM read_parquet(['s3://acme-etl/a.parquet', 's3://other/b.parquet'])",
    "SELECT * FROM read_parquet('s3://acme-etl/a.parquet') r, read_text('/etc/hosts') t",
    "COPY lake.main.orders TO '/tmp/o.csv'",
    "COPY lake.main.events FROM 's3://other/e.csv'",
    "SELECT * FROM read_parquet(e'/etc/hosts')",
    // shapes the gateway does not read completely
    "SELECT * FROM read_parquet('s3://acme-etl/x' || '.parquet')",
    "SELECT * FROM read_csv('s3://acme-etl/x.csv', '/etc/hosts')",
    "SELECT * FROM query('SELECT * FROM read_parquet(''s3://acme-etl/x.parquet'')')",
    "EXPLAIN ANALYZE COPY lake.main.orders TO 's3://acme-etl/out/o.csv'",
    "PREPARE p AS COPY lake.main.orders TO 's3://acme-etl/out/o.csv'",
    "SELECT 1; COPY lake.main.orders TO 's3://acme-etl/out/o.csv'",
    "COPY lake.main.orders TO 's3://acme-etl/out/' (FILENAME_PATTERN '../x_{i}')",
    // a file in a table position, whatever the location
    "SELECT * FROM 's3://acme-etl/in/x.parquet'",
    "SELECT * FROM \"s3://acme-etl/in/x.parquet\"",
    "SELECT * FROM '/srv/qod/acme/in/x.csv'",
    "SUMMARIZE 's3://acme-etl/in/x.parquet'",
    // never admitted by a location (not of class File: they read further files the location
    // check never sees, or take SQL text)
    "SELECT * FROM iceberg_scan('s3://acme-etl/in/t')",
    "SELECT * FROM sql_auto_complete('SELECT * FROM ''/srv/qod/acme/in/')",
    "EXPORT DATABASE 's3://acme-etl/dump'",
    "IMPORT DATABASE 's3://acme-etl/dump'",
    "ATTACH 's3://acme-etl/x.duckdb' AS x",
    "LOAD 's3://acme-etl/x.duckdb_extension'",
    "SELECT * FROM histogram('s3://acme-etl/in/x.csv', a)"
  )

  "FileAccessScreen" should "admit a reader or a lone COPY on authorized locations" in
    admittedForms.foreach { sql =>
      withClue(sql)(FileAccessScreen.find(sql, admission) shouldBe None)
    }

  it should "refuse every neighbor it must still refuse" in
    stillRefused.foreach { sql =>
      withClue(sql)(FileAccessScreen.find(sql, admission) shouldBe defined)
    }

  it should "refuse every admitted form without an admission" in
    admittedForms.foreach(sql => withClue(sql)(FileAccessScreen.find(sql) shouldBe defined))

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  private def perm(t: String, v: String) =
    RolePermission(s"rp-$t-$v", "role-1", "lake", "main", t, v, None)
  private val alice = EffectiveSet(
    RbacUser("u-1", Some("t-1"), "alice", "user"),
    Nil,
    Nil,
    List(perm("orders", "RO"), perm("events", "RW")),
    Nil
  )
  private val validator =
    new PostgresAclValidator(tenantCatalogs = _ => Set("lake"), filteredMetadata = true)
  private def validate(sql: String, a: FileAdmission) =
    validator.validate(
      ValidationContext(
        username = "alice",
        database = "t-1/td-1/p-1",
        statement = sql,
        peer = "conn-1",
        defaultDatabase = Some("lake"),
        defaultSchema = Some("main"),
        effectiveSet = Some(alice),
        fileAdmission = a
      )
    )

  "PostgresAclValidator" should "admit an authorized read with no grant, and judge the table side" in {
    validate("SELECT * FROM read_parquet('s3://acme-etl/in/x.parquet')", admission) shouldBe Allowed
    validate(
      "INSERT INTO lake.main.events SELECT * FROM read_csv('s3://acme-etl/in/e.csv')",
      admission
    ) shouldBe Allowed
    validate("COPY lake.main.events FROM 's3://acme-etl/in/e.csv'", admission) shouldBe Allowed
    validate("COPY events FROM 's3://acme-etl/in/e.csv'", admission) shouldBe Allowed
    validate("COPY lake.main.orders TO 's3://acme-etl/out/o.parquet'", admission) shouldBe Allowed
    validate(
      "COPY (SELECT id FROM lake.main.orders) TO 's3://acme-etl/out/o.parquet'",
      admission
    ) shouldBe Allowed
  }

  it should "still refuse a table side the grants do not cover" in
    List(
      // orders is RO: no write into it, from a file or otherwise
      "INSERT INTO lake.main.orders SELECT * FROM read_csv('s3://acme-etl/in/o.csv')",
      "COPY lake.main.orders FROM 's3://acme-etl/in/o.csv'",
      // secret is not granted at all
      "COPY lake.main.secret TO 's3://acme-etl/out/s.parquet'",
      "COPY (SELECT * FROM lake.main.secret) TO 's3://acme-etl/out/s.parquet'",
      "SELECT * FROM lake.main.secret, read_parquet('s3://acme-etl/in/x.parquet')"
    ).foreach(sql => withClue(sql)(validate(sql, admission) shouldBe a[Denied]))

  it should "refuse every admitted form under the closed admission" in
    admittedForms.foreach(sql =>
      withClue(sql)(validate(sql, FileAdmission.Closed) shouldBe a[Denied])
    )

  "AccessGate" should "refuse an unauthorized location as not wildcard-coverable" in {
    AccessGate.evaluate(
      "SELECT * FROM read_csv('/etc/hosts')",
      cfg,
      "lake",
      true,
      clientDataScans = false,
      admission = admission
    ) match
      case GateOutcome.Refuse(reason, coverable, _) =>
        coverable shouldBe false
        reason should include("tenant-scoped")
      case other => fail(s"expected Refuse, got $other")
  }

  "LockdownScreen" should "admit authorized locations, a DuckLake bucket's other folders included" in {
    val lockdown = FileAdmission(loc =>
      (loc.startsWith("s3://lakebucket/") && !loc.startsWith("s3://lakebucket/lake-1/")) ||
        loc.startsWith("/srv/qod/acme/in/")
    )
    List(
      "SELECT * FROM read_csv('/srv/qod/acme/in/x.csv')",
      "SELECT * FROM read_parquet('s3://lakebucket/etl/x.parquet')",
      "SELECT * FROM glob('/srv/qod/acme/in/*')",
      "COPY t FROM '/srv/qod/acme/in/x.csv'",
      "COPY (SELECT 1) TO 's3://lakebucket/etl/x.parquet'"
    ).foreach { sql =>
      withClue(sql)(LockdownScreen.screen(sql, Set("lakebucket"), lockdown) shouldBe None)
    }
    List(
      "SELECT * FROM read_csv('/etc/hosts')",
      "SELECT * FROM read_parquet('s3://lakebucket/lake-1/main/t/x.parquet')",
      "SELECT * FROM read_parquet('s3://lakebucket/lake-10' || '/x.parquet')",
      "COPY t TO '/tmp/x.csv'",
      "SELECT * FROM '/srv/qod/acme/in/x.csv'",
      "SELECT * FROM getenv('HOME')"
    ).foreach { sql =>
      withClue(sql)(LockdownScreen.screen(sql, Set("lakebucket"), lockdown) shouldBe defined)
    }
    LockdownScreen
      .screen(
        "SELECT * FROM read_parquet('s3://lakebucket/lake-1/main/t/x.parquet')",
        Set("lakebucket"),
        lockdown
      )
      .get should include("lakebucket")
  }

  "fileAdmissionFor" should "give the principal's own rules, and nothing else" in {
    // Alice holds an S3 credential for s3://acme-etl/in/ (decision 23); her tenant authorizes a folder.
    val rules = LocationRules(
      s3Scopes = List("s3://acme-etl/in/"),
      tenantFolders = List("/srv/qod/acme/in/"),
      dataFolders = List("s3://acme-etl/lake-1/"),
      filesRoot = Some("/srv/qod/")
    )
    val rulesFor: (String, String) => Option[LocationRules] = (tenant, user) =>
      Option.when(tenant == "t-1")(if user == "u-1" then rules else rules.copy(s3Scopes = Nil))
    val forAlice = FlightSqlRouter.fileAdmissionFor(Some(alice), rulesFor)
    forAlice.admits("s3://acme-etl/in/x.parquet") shouldBe true
    forAlice.admits("s3://acme-etl/out/x.parquet") shouldBe false
    forAlice.admits("/srv/qod/acme/in/x.csv") shouldBe true
    forAlice.admits("s3://acme-etl/lake-1/main/t/x.parquet") shouldBe false
    forAlice.admits("/srv/qod/acme/other/x.csv") shouldBe false
    // Carol, of the same tenant, holds no credential: no s3 location, the tenant's folders still.
    val carol = alice.copy(user = RbacUser("u-3", Some("t-1"), "carol", "user"))
    FlightSqlRouter
      .fileAdmissionFor(Some(carol), rulesFor)
      .admits("s3://acme-etl/in/x.parquet") shouldBe false
    FlightSqlRouter
      .fileAdmissionFor(Some(carol), rulesFor)
      .admits("/srv/qod/acme/in/x.csv") shouldBe true
    val bob = alice.copy(user = RbacUser("u-2", Some("t-2"), "bob", "user"))
    FlightSqlRouter
      .fileAdmissionFor(Some(bob), rulesFor)
      .admits("s3://acme-etl/in/x") shouldBe false
    // On the local runtime a clean absolute path is judged after the node's resolution; a dotted,
    // empty-segment or relative one never is.
    val viaLink = FlightSqlRouter.fileAdmissionFor(
      Some(alice),
      rulesFor,
      p => if p.contains("dangling") then None else Some(p.replace("/tmp-link/", "/srv/qod/"))
    )
    viaLink.admits("/tmp-link/acme/in/x.csv") shouldBe true
    viaLink.admits("/tmp-link/acme/in/../in/x.csv") shouldBe false
    viaLink.admits("/tmp-link/acme//in/x.csv") shouldBe false
    // a path the node cannot resolve (a link that leads nowhere) is refused, never judged as given
    viaLink.admits("/srv/qod/acme/in/dangling") shouldBe false
    forAlice.admits("/srv/qod/acme/in/dangling") shouldBe true
    forAlice.admits("/tmp-link/acme/in/x.csv") shouldBe false
    val root = alice.copy(user = RbacUser("u-0", None, "root", "admin"))
    FlightSqlRouter
      .fileAdmissionFor(Some(root), rulesFor)
      .admits("s3://acme-etl/in/x") shouldBe false
    FlightSqlRouter.fileAdmissionFor(None, rulesFor).admits("s3://acme-etl/in/x") shouldBe false
    val system = alice.copy(system = true)
    FlightSqlRouter.fileAdmissionFor(Some(system), rulesFor).admits("s3://acme-etl/in/x") shouldBe
      false
  }

  it should "admit nothing when the rules cannot be read, and read them only when asked" in {
    var reads                                              = 0
    val failing: (String, String) => Option[LocationRules] = (_, _) =>
      reads += 1
      throw new IllegalStateException("store down")
    val admission = FlightSqlRouter.fileAdmissionFor(Some(alice), failing)
    reads shouldBe 0
    admission.admits("s3://acme-etl/in/x.parquet") shouldBe false
    admission.admits("/srv/qod/acme/in/x.csv") shouldBe false
    reads shouldBe 1
    // a store failure (LocationRulesSource answered Left, wired as None)
    FlightSqlRouter
      .fileAdmissionFor(Some(alice), (_, _) => None)
      .admits("s3://acme-etl/in/x.parquet") shouldBe false
    FileAccessScreen.find(
      "SELECT * FROM read_parquet('s3://acme-etl/in/x.parquet')",
      admission
    ) shouldBe defined
  }

  // Task 12 review, implementer concern 2: the stand-in reprints a COPY's table name verbatim, and
  // JSqlParser keeps a doubled quote doubled where DuckDB reads one. DottedIdentifierScreen refuses
  // such a name on the COPY text itself, before the stand-in is parsed, in every consumer.
  private val quotedNameCopies = List(
    "COPY \"a\"\"b\" TO 's3://acme-etl/out/x.csv'",
    "COPY \"secret@x\" FROM 's3://acme-etl/in/x.csv'"
  )

  "a COPY of a doubled-quote or odd quoted name" should "be refused before its stand-in is parsed" in {
    quotedNameCopies.foreach { sql =>
      withClue(sql) {
        FileLocations.copy(sql) shouldBe defined
        DottedIdentifierScreen.find(sql) shouldBe defined
        validate(sql, admission) shouldBe a[Denied]
        LockdownScreen.screen(sql, Set.empty, admission) shouldBe defined
      }
    }
    val guard = new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(Map(("lake", "main", "customer") -> List("c_id"))),
      clsEnabled = true,
      rlsEnabled = true
    )
    val withRows = alice.copy(rowPolicies =
      List(RoleRowPolicy("rp-1", "r-1", "*", "main", "customer", "c_id = 1"))
    )
    quotedNameCopies.foreach { sql =>
      withClue(sql)(
        guard.check(
          sql,
          StatementKind.Dml,
          withRows,
          SchemaContext(Some("lake"), Some("main"), Set.empty)
        ) shouldBe a[GuardOutcome.Deny]
      )
    }
  }

  // Task 12 review I1: the access check authorizes a COPY on its stand-in, and admits a metadata
  // read there only because the edge filter narrows it; the filter must leave the stand-in alone.
  private val metadataFilter =
    new ai.starlake.quack.edge.meta.MetadataFilterRewriter(enabled = true)
  private val schemaCtx                   = SchemaContext(Some("lake"), Some("main"), Set("lake"))
  private def listingRefusal(sql: String) =
    FlightSqlRouter.copyListingRefusal(sql, s => metadataFilter.rewrite(s, alice, schemaCtx))

  "a COPY of filtered metadata" should "be refused, whatever form names the listing" in {
    List(
      "COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "COPY (SHOW TABLES FROM tpch1) TO 's3://acme-etl/out/t.csv'"
    ).foreach { sql =>
      withClue(sql) {
        FileLocations.copy(sql) shouldBe None
        FileAccessScreen.find(sql, admission) shouldBe defined
        validate(sql, admission) shouldBe a[Denied]
      }
    }
    List(
      "COPY (SELECT table_name, column_name FROM information_schema./**/columns) TO " +
        "'s3://acme-etl/out/c.csv'",
      "COPY (SELECT table_name FROM information_schema.tables) TO 's3://acme-etl/out/t.csv'",
      "COPY information_schema./**/tables TO 's3://acme-etl/out/t.csv'",
      "COPY lake.information_schema.columns TO 's3://acme-etl/out/c.csv'"
    ).foreach { sql =>
      withClue(sql) {
        // the access check admits it (the filtered-metadata implicit admit, on the stand-in), so
        // the filter step is what refuses it
        validate(sql, admission) shouldBe Allowed
        listingRefusal(sql) shouldBe defined
      }
    }
  }

  it should "leave an ordinary COPY alone" in {
    List(
      "COPY (SELECT * FROM main.orders) TO 's3://acme-etl/out/o.csv'",
      "COPY lake.main.orders TO 's3://acme-etl/out/o.csv'",
      "COPY lake.main.events FROM 's3://acme-etl/in/e.csv'",
      "SELECT * FROM read_parquet('s3://acme-etl/in/x.parquet')"
    ).foreach(sql => withClue(sql)(listingRefusal(sql) shouldBe None))
    validate("COPY (SELECT * FROM main.orders) TO 's3://acme-etl/out/o.csv'", admission) shouldBe
      Allowed
  }

  // Task 12 review M3: a rewrite of a statement holding an admitted reader is still compared.
  "rewriteReachesBeyond" should "compare a rewrite of an admitted file read under the admission" in {
    val original =
      "SELECT o.id FROM lake.main.orders o, read_parquet('s3://acme-etl/in/x.parquet') r"
    val rewritten = original + ", lake.main.secret s"
    AccessGate.rewriteReachesBeyond(original, rewritten, cfg, "lake", false, admission) match
      case Some(reason) => reason should include("lake.main.secret")
      case None         => fail("the extra table went unnoticed")
    AccessGate.rewriteReachesBeyond(original, original, cfg, "lake", false, admission) shouldBe None
    // without the admission the original is refused, so nothing is compared (the old behavior)
    AccessGate.rewriteReachesBeyond(original, rewritten, cfg, "lake", false) shouldBe None
  }
