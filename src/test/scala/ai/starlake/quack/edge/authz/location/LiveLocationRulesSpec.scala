package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.model.TenantLocation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

class LiveLocationRulesSpec extends AnyFlatSpec with Matchers:

  private def loc(id: String, tenant: String, kind: String, location: String) =
    TenantLocation(id, tenant, kind, location, 0L, None)

  // Each user's own S3 credential scopes (decision 23); a malformed stored one never admits.
  private val Scopes = Map(
    "u-1" -> List("s3://acme-etl/", "s3://acme-etl/", "not-a-scope", "/srv/qod-files/acme/in/"),
    "u-3" -> List("s3://other-etl/in/"),
    "u-4" -> List("s3://lake/root/salesdb/main/", "s3://lake/")
  )

  private val Locations = Map(
    "acme" -> List(
      loc("tl-3", "acme", "local", "/srv/qod-files/acme/in/"),
      loc("tl-4", "acme", "local", "/srv/qod-files/globex/in/"),
      loc("tl-5", "acme", "local", "/etc/")
    )
  )

  private final class Fixture(filesRoot: Option[String] = Some("/srv/qod-files")):
    val clock      = new AtomicLong(1_000_000L)
    val paths      = new AtomicReference(List("s3://Lake/acme_tpch-1a2b3c4d", "./ducklake/tpch"))
    val storeReads = new AtomicInteger(0)
    val storeFails = new AtomicReference(false)
    val rules      = new LiveLocationRules(
      dataPaths = () => paths.get(),
      unpurgedPrefixes = () => {
        storeReads.incrementAndGet()
        if storeFails.get() then throw new RuntimeException("pg down")
        List("s3://managed/acme_old-0f0f0f0f/")
      },
      tenantLocations = t => Locations.getOrElse(t, Nil),
      userScopes = u => Right(Scopes.getOrElse(u, Nil)),
      filesRoot = filesRoot,
      sameHost = false,
      clock = () => clock.get()
    )

  "rulesFor" should "keep the user's own S3 scopes and the tenant's folders under root/tenant" in {
    val f = new Fixture()
    val r = f.rules.rulesFor("acme", "u-1").toOption.get
    r.s3Scopes shouldBe List("s3://acme-etl/")
    r.tenantFolders shouldBe List("/srv/qod-files/acme/in/")
    r.filesRoot shouldBe Some("/srv/qod-files/")
  }

  it should "build the deny set from the supervisor and the store" in {
    val f = new Fixture()
    f.rules.rulesFor("acme", "u-1").toOption.get.dataFolders.toSet shouldBe
      Set("s3://lake/acme_tpch-1a2b3c4d/", "./ducklake/tpch", "s3://managed/acme_old-0f0f0f0f/")
  }

  it should "give a user with no credential and a tenant with no locations nothing but the deny set" in {
    val f = new Fixture()
    val r = f.rules.rulesFor("globex", "u-2").toOption.get
    r.s3Scopes shouldBe Nil
    r.tenantFolders shouldBe Nil
    r.dataFolders should not be empty
  }

  it should "authorize no folder without a files root, or with a relative one" in
    List(None, Some("files"), Some("  ")).foreach { root =>
      val r = new Fixture(root).rules.rulesFor("acme", "u-1").toOption.get
      r.filesRoot shouldBe None
      r.tenantFolders shouldBe Nil
    }

  it should "keep a removed data path for RecentMs" in {
    val f = new Fixture()
    f.rules.rulesFor("acme", "u-1")
    f.paths.set(List("./ducklake/tpch"))
    f.rules.rulesFor("acme", "u-1").toOption.get.dataFolders should contain(
      "s3://lake/acme_tpch-1a2b3c4d/"
    )
    f.clock.addAndGet(LiveLocationRules.RecentMs + 1)
    val later = f.rules.rulesFor("acme", "u-1").toOption.get.dataFolders
    later should not contain "s3://lake/acme_tpch-1a2b3c4d/"
  }

  it should "read the store at most once per StoreTtlMs" in {
    val f = new Fixture()
    f.rules.rulesFor("acme", "u-1")
    f.rules.rulesFor("acme", "u-1")
    f.storeReads.get() shouldBe 1
    f.clock.addAndGet(LiveLocationRules.StoreTtlMs)
    f.rules.rulesFor("acme", "u-1")
    f.storeReads.get() shouldBe 2
  }

  it should "be unavailable while the store fails, and recover" in {
    val f = new Fixture()
    f.storeFails.set(true)
    f.rules.rulesFor("acme", "u-1") shouldBe Left("the database data folders could not be read")
    f.storeFails.set(false)
    f.rules.rulesFor("acme", "u-1").isRight shouldBe true
  }

  it should "refuse every path of a bucket whose root is a data path, either spelling" in
    List("s3://acme-etl/", "s3://acme-etl", "s3://ACME-ETL").foreach { root =>
      val f = new Fixture()
      f.paths.set(List(root))
      val r = f.rules.rulesFor("acme", "u-1").toOption.get
      // The user's own scope s3://acme-etl/ now lies at a data folder: it is withheld.
      r.s3Scopes shouldBe Nil
      val held = r.copy(s3Scopes = List("s3://acme-etl/"))
      List("s3://acme-etl/", "s3://acme-etl/in/x.csv", "s3://acme-etl/**").foreach { p =>
        withClue(s"$root / $p") {
          LocationChecker.check(p, r).isLeft shouldBe true
          LocationChecker.check(p, held) shouldBe Left(s"$p ${LocationChecker.DataFolder}")
        }
      }
    }

  it should "deny every local location for an unreadable local data path, fail closed" in {
    val f = new Fixture()
    f.paths.set(List("/srv/qod-files/acme/in/x\u0000y"))
    val r = f.rules.rulesFor("acme", "u-1").toOption.get
    LocationChecker.check("/srv/qod-files/acme/in/x.csv", r) shouldBe
      Left(
        "/srv/qod-files/acme/in/x.csv is a database data folder (a relative data path of this " +
          "deployment may lie there)"
      )
    LocationChecker.check("s3://acme-etl/in/x.csv", r) shouldBe Right(())
  }

  "on the local runtime" should "drop a folder whose real path leaves the real root/tenant" in
    List(
      (p: String) => p.replace("/srv/qod-files/acme/in/", "/srv/qod-files/globex/in/"),
      (p: String) => p.replace("/srv/qod-files/acme/in/", "/etc/")
    ).foreach { real =>
      val r = new LiveLocationRules(
        dataPaths = () => Nil,
        unpurgedPrefixes = () => Nil,
        tenantLocations = t => Locations.getOrElse(t, Nil),
        userScopes = u => Right(Scopes.getOrElse(u, Nil)),
        filesRoot = Some("/srv/qod-files/"),
        sameHost = true,
        realPath = real
      ).rulesFor("acme", "u-1").toOption.get
      r.tenantFolders shouldBe Nil
      LocationChecker.check("/srv/qod-files/globex/in/x.csv", r).isLeft shouldBe true
    }

  it should "use real paths for the root and the folders" in {
    val real = (p: String) => p.replace("/srv/", "/private/srv/")
    val r    = new LiveLocationRules(
      dataPaths = () => List("/srv/qod-files/acme/in/lake"),
      unpurgedPrefixes = () => Nil,
      tenantLocations = t => Locations.getOrElse(t, Nil),
      userScopes = u => Right(Scopes.getOrElse(u, Nil)),
      filesRoot = Some("/srv/qod-files/"),
      sameHost = true,
      realPath = real
    ).rulesFor("acme", "u-1").toOption.get
    r.filesRoot shouldBe Some("/private/srv/qod-files/")
    r.tenantFolders shouldBe List("/private/srv/qod-files/acme/in/")
    r.dataFolders.toSet shouldBe
      Set("/srv/qod-files/acme/in/lake/", "/private/srv/qod-files/acme/in/lake/")
    LocationChecker.check("/private/srv/qod-files/acme/in/x.csv", r) shouldBe Right(())
    LocationChecker.check("/private/srv/qod-files/acme/in/lake/x", r).isLeft shouldBe true
  }

  "rulesFor" should "give each user of a tenant their own scopes" in {
    val f = new Fixture()
    f.rules.rulesFor("acme", "u-3").toOption.get.s3Scopes shouldBe List("s3://other-etl/in/")
    f.rules.rulesFor("acme", "u-3").toOption.get.tenantFolders shouldBe
      List("/srv/qod-files/acme/in/")
    LocationChecker
      .check("s3://acme-etl/in/x.csv", f.rules.rulesFor("acme", "u-3").toOption.get)
      .isLeft shouldBe true
  }

  "dataFolderOverlap" should "refuse a scope at a data folder, admit its ancestor, and fail closed" in {
    val f = new Fixture()
    f.rules.dataFolderOverlap("s3://lake/acme_tpch-1a2b3c4d/main/") shouldBe
      Right(Some(s"s3://lake/acme_tpch-1a2b3c4d/main/ ${LocationChecker.DataFolder}"))
    f.rules.dataFolderOverlap("s3://lake/") shouldBe Right(None)
    f.rules.dataFolderOverlap("s3://managed/acme_old-0f0f0f0f/").map(_.isDefined) shouldBe Right(
      true
    )
    f.storeFails.set(true)
    f.clock.addAndGet(LiveLocationRules.StoreTtlMs)
    f.rules.dataFolderOverlap("s3://lake/") shouldBe Left(
      "the database data folders could not be read"
    )
  }

  it should "reuse the deny set while nothing it is built from changes" in {
    val f     = new Fixture()
    val first = f.rules.rulesFor("acme", "u-1").toOption.get.dataFolders
    f.rules.rulesFor("acme", "u-3").toOption.get.dataFolders should be theSameInstanceAs first
    f.paths.set(List("./ducklake/tpch"))
    val second = f.rules.rulesFor("acme", "u-1").toOption.get.dataFolders
    second should not be theSameInstanceAs(first)
    // The dropped path is still denied for RecentMs, so the set itself is the same.
    second.toSet shouldBe first.toSet
    f.rules.rulesFor("acme", "u-1").toOption.get.dataFolders should be theSameInstanceAs second
  }

  "a user's scope" should "stop counting once a database lands at it or above it" in {
    val f = new Fixture()
    f.rules.rulesFor("acme", "u-4").toOption.get.s3Scopes shouldBe
      List("s3://lake/root/salesdb/main/", "s3://lake/")
    // A DuckLake database created afterwards at an ancestor of the scope.
    f.paths.set(f.paths.get() :+ "s3://lake/root/salesdb")
    val r = f.rules.rulesFor("acme", "u-4").toOption.get
    r.s3Scopes shouldBe List("s3://lake/")
    LocationChecker.admittedBy("s3://lake/root/salesdb/main/t/x.parquet", r) shouldBe
      Some("s3://lake/")
    LocationChecker.check("s3://lake/root/salesdb/main/t/x.parquet", r).isLeft shouldBe true
  }

  it should "make the rules unavailable while the user's credentials cannot be read" in {
    val r = new LiveLocationRules(
      dataPaths = () => Nil,
      unpurgedPrefixes = () => Nil,
      tenantLocations = t => Locations.getOrElse(t, Nil),
      userScopes = _ => Left("the users' S3 credentials could not be read"),
      filesRoot = Some("/srv/qod-files"),
      sameHost = false
    )
    r.rulesFor("acme", "u-1") shouldBe Left("the users' S3 credentials could not be read")
  }

  "on the local runtime" should "resolve the root and the folders once per rebuild, not per statement" in {
    val calls = new AtomicInteger(0)
    val clock = new AtomicLong(1_000_000L)
    val r     = new LiveLocationRules(
      dataPaths = () => Nil,
      unpurgedPrefixes = () => Nil,
      tenantLocations = t => Locations.getOrElse(t, Nil),
      userScopes = u => Right(Scopes.getOrElse(u, Nil)),
      filesRoot = Some("/srv/qod-files/"),
      sameHost = true,
      clock = () => clock.get(),
      realPath = p => { calls.incrementAndGet(); p }
    )
    val first = r.rulesFor("acme", "u-1").toOption.get
    val once  = calls.get()
    once should be > 0
    (1 to 5).foreach(_ =>
      r.rulesFor("acme", "u-3").toOption.get shouldBe first.copy(
        s3Scopes = List("s3://other-etl/in/")
      )
    )
    calls.get() shouldBe once
    clock.addAndGet(LiveLocationRules.EntryTtlMs)
    r.rulesFor("acme", "u-1").toOption.get.tenantFolders shouldBe first.tenantFolders
    calls.get() should be > once
  }

  "a data path the checker cannot place" should "be named once in a WARN, with its tenant-db" in {
    // Off the local runtime a relative data path may lie anywhere on a node, so every tenant's
    // local locations are refused while it is listed (fail closed): the operator must learn which
    // database causes it (Phase C ledger T5C-2). An absolute path or a bucket is placed: no WARN.
    val paths = new AtomicReference(List("s3://lake/acme_tpch/", "/srv/lake/acme_x/", "./lake/t"))
    val rules = new LiveLocationRules(
      dataPaths = () => paths.get(),
      unpurgedPrefixes = () => Nil,
      tenantLocations = _ => Nil,
      userScopes = _ => Right(Nil),
      filesRoot = Some("/srv/qod-files"),
      sameHost = false,
      owners = p => if p == "./lake/t" then List("tenant-db acme/t (td-1)") else Nil
    )
    val (_, events) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture
      .logged(classOf[LiveLocationRules].getName) {
        (1 to 3).foreach(_ => rules.rulesFor("acme", "u-1"))
        paths.set(paths.get() :+ "s3://lake/other/")
        rules.rulesFor("acme", "u-1")
      }
    val warned = events
      .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
      .map(_.getFormattedMessage)
    warned shouldBe List(
      "location rules: tenant-db acme/t (td-1): its data path cannot be placed on the nodes " +
        "(a relative path off the local runtime, or one this host cannot parse); every " +
        "tenant's local file locations are refused until it is fixed"
    )
    LocationChecker.unplaceable("./lake/t") shouldBe true
    LocationChecker.unplaceable("/srv/lake/acme_x/") shouldBe false
    LocationChecker.unplaceable("s3://lake/acme_tpch/") shouldBe false
    LocationChecker.unplaceable(DataFolders.Unplaced) shouldBe false
  }
