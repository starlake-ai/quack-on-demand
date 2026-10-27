package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.model.TenantLocation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

class LiveLocationRulesSpec extends AnyFlatSpec with Matchers:

  private val Creds = Map("s3_access_key_id" -> "AKIA", "s3_secret_access_key" -> "s")

  private def loc(
      id: String,
      tenant: String,
      kind: String,
      location: String,
      store: Map[String, String] = Map.empty
  ) = TenantLocation(id, tenant, kind, location, store, 0L, None)

  private val Locations = Map(
    "acme" -> List(
      loc("tl-1", "acme", "s3", "s3://acme-etl/", Creds),
      loc("tl-2", "acme", "s3", "s3://no-creds/"),
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
      filesRoot = filesRoot,
      sameHost = false,
      clock = () => clock.get()
    )

  "rulesFor" should "keep the tenant's credentialed buckets and its folders under root/tenant" in {
    val f = new Fixture()
    val r = f.rules.rulesFor("acme").toOption.get
    r.tenantBuckets shouldBe Set("acme-etl")
    r.tenantFolders shouldBe List("/srv/qod-files/acme/in/")
    r.filesRoot shouldBe Some("/srv/qod-files/")
  }

  it should "build the deny set from the supervisor and the store" in {
    val f = new Fixture()
    f.rules.rulesFor("acme").toOption.get.dataFolders.toSet shouldBe
      Set("s3://lake/acme_tpch-1a2b3c4d/", "./ducklake/tpch", "s3://managed/acme_old-0f0f0f0f/")
  }

  it should "give a tenant with no locations nothing but the deny set" in {
    val f = new Fixture()
    val r = f.rules.rulesFor("globex").toOption.get
    r.tenantBuckets shouldBe Set.empty
    r.tenantFolders shouldBe Nil
    r.dataFolders should not be empty
  }

  it should "authorize no folder without a files root, or with a relative one" in
    List(None, Some("files"), Some("  ")).foreach { root =>
      val r = new Fixture(root).rules.rulesFor("acme").toOption.get
      r.filesRoot shouldBe None
      r.tenantFolders shouldBe Nil
    }

  it should "keep a removed data path for RecentMs" in {
    val f = new Fixture()
    f.rules.rulesFor("acme")
    f.paths.set(List("./ducklake/tpch"))
    f.rules.rulesFor("acme").toOption.get.dataFolders should contain(
      "s3://lake/acme_tpch-1a2b3c4d/"
    )
    f.clock.addAndGet(LiveLocationRules.RecentMs + 1)
    val later = f.rules.rulesFor("acme").toOption.get.dataFolders
    later should not contain "s3://lake/acme_tpch-1a2b3c4d/"
  }

  it should "read the store at most once per StoreTtlMs" in {
    val f = new Fixture()
    f.rules.rulesFor("acme")
    f.rules.rulesFor("acme")
    f.storeReads.get() shouldBe 1
    f.clock.addAndGet(LiveLocationRules.StoreTtlMs)
    f.rules.rulesFor("acme")
    f.storeReads.get() shouldBe 2
  }

  it should "be unavailable while the store fails, and recover" in {
    val f = new Fixture()
    f.storeFails.set(true)
    f.rules.rulesFor("acme") shouldBe Left("the database data folders could not be read")
    f.storeFails.set(false)
    f.rules.rulesFor("acme").isRight shouldBe true
  }

  it should "refuse every path of a bucket whose root is a data path, either spelling" in
    List("s3://acme-etl/", "s3://acme-etl", "s3://ACME-ETL").foreach { root =>
      val f = new Fixture()
      f.paths.set(List(root))
      val r = f.rules.rulesFor("acme").toOption.get
      List("s3://acme-etl/", "s3://acme-etl/in/x.csv", "s3://acme-etl/**").foreach(p =>
        withClue(s"$root / $p")(
          LocationChecker.check(p, r) shouldBe Left(s"$p ${LocationChecker.DataFolder}")
        )
      )
    }

  it should "deny every local location for an unreadable local data path, fail closed" in {
    val f = new Fixture()
    f.paths.set(List("/srv/qod-files/acme/in/x\u0000y"))
    val r = f.rules.rulesFor("acme").toOption.get
    LocationChecker.check("/srv/qod-files/acme/in/x.csv", r).isLeft shouldBe true
    LocationChecker.check("s3://acme-etl/in/x.csv", r) shouldBe Right(())
  }

  "on the local runtime" should "use real paths for the root and the folders" in {
    val real = (p: String) => p.replace("/srv/", "/private/srv/")
    val r    = new LiveLocationRules(
      dataPaths = () => List("/srv/qod-files/acme/in/lake"),
      unpurgedPrefixes = () => Nil,
      tenantLocations = t => Locations.getOrElse(t, Nil),
      filesRoot = Some("/srv/qod-files/"),
      sameHost = true,
      realPath = real
    ).rulesFor("acme").toOption.get
    r.filesRoot shouldBe Some("/private/srv/qod-files/")
    r.tenantFolders shouldBe List("/private/srv/qod-files/acme/in/")
    r.dataFolders.toSet shouldBe
      Set("/srv/qod-files/acme/in/lake/", "/private/srv/qod-files/acme/in/lake/")
    LocationChecker.check("/private/srv/qod-files/acme/in/x.csv", r) shouldBe Right(())
    LocationChecker.check("/private/srv/qod-files/acme/in/lake/x", r).isLeft shouldBe true
  }
