package ai.starlake.quack.edge.authz.location

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

class DataFoldersSpec extends AnyFlatSpec with Matchers:

  private def remote(path: String): List[String] = DataFolders.of(path, sameHost = false, identity)

  "an object-store data path" should "become an s3 folder whatever its scheme" in {
    remote("s3://Lake-B/acme/") shouldBe List("s3://lake-b/acme/")
    remote("s3://lake-b/acme") shouldBe List("s3://lake-b/acme/")
    remote("s3://lake-b") shouldBe List("s3://lake-b/")
    remote("s3://lake-b//acme//x/") shouldBe List("s3://lake-b/acme/x/")
    List("s3a", "s3n", "r2", "gs", "gcs", "az", "azure").foreach(s =>
      remote(s"$s://lake-b/acme/") shouldBe List("s3://lake-b/acme/")
    )
    remote("abfss://cont@acct.dfs.core.windows.net/p/q") shouldBe List("s3://cont/p/q/")
  }

  it should "yield both bucket readings of an http(s) URL" in {
    remote("https://lakeb.s3.amazonaws.com/acme/") shouldBe
      List("s3://lakeb/acme/", "s3://acme/")
    remote("http://minio:9000/lakeb/acme") shouldBe List(
      "s3://minio/lakeb/acme/",
      "s3://lakeb/acme/"
    )
  }

  it should "be dropped for a scheme a tenant can never name" in {
    remote("hf://datasets/x/") shouldBe Nil
    remote("md://db") shouldBe Nil
    remote("") shouldBe Nil
    remote("   ") shouldBe Nil
  }

  "a file: data path" should "be the local path it names" in {
    remote("file:///srv/qod-files/acme/out/lake/") shouldBe List("/srv/qod-files/acme/out/lake/")
    remote("FILE:///srv/lake") shouldBe List("/srv/lake/")
    remote("file://localhost/srv/lake") shouldBe List("/srv/lake/")
    remote("file:/srv/./lake") shouldBe List("/srv/lake/")
    remote("file:///C:/qod/lake") shouldBe List("C:/qod/lake/")
    DataFolders.of("file:ducklake/tpch", sameHost = true, identity) shouldBe
      List(Paths.get("ducklake/tpch").toAbsolutePath.normalize.toString + "/")
  }

  it should "fail closed for another host, no path, or a relative path off the local runtime" in {
    remote("file://nas/srv/lake") shouldBe List(DataFolders.Unplaced)
    remote("file://") shouldBe List(DataFolders.Unplaced)
    remote("file:") shouldBe List(DataFolders.Unplaced)
    remote("file:ducklake/tpch") shouldBe List("ducklake/tpch")
  }

  it should "deny its folder to the checker, and every local location when it cannot be placed" in {
    val rules = LocationRules(
      tenantBuckets = Set("acme-etl"),
      tenantFolders = List("/srv/qod-files/acme/out/"),
      dataFolders = remote("file:///srv/qod-files/acme/out/lake"),
      filesRoot = Some("/srv/qod-files/")
    )
    LocationChecker.check("/srv/qod-files/acme/out/lake/x.parquet", rules) shouldBe
      Left(s"/srv/qod-files/acme/out/lake/x.parquet ${LocationChecker.DataFolder}")
    LocationChecker.check("/srv/qod-files/acme/out/x.parquet", rules) shouldBe Right(())
    val unplaced = rules.copy(dataFolders = remote("file://nas/srv/lake"))
    LocationChecker.check("/srv/qod-files/acme/out/x.parquet", unplaced).isLeft shouldBe true
    LocationChecker.check("s3://acme-etl/x.parquet", unplaced) shouldBe Right(())
  }

  "the unplaced marker" should "stay as written on every runtime" in {
    remote(DataFolders.Unplaced) shouldBe List(DataFolders.Unplaced)
    DataFolders.of(DataFolders.Unplaced, sameHost = true, identity) shouldBe
      List(DataFolders.Unplaced)
  }

  "an absolute local data path" should "be normalized lexically" in {
    remote("/var/qod//lake/./a/../b") shouldBe List("/var/qod/lake/b/")
    remote("C:\\qod\\lake") shouldBe List("C:/qod/lake/")
    remote("c:/qod/./lake/") shouldBe List("C:/qod/lake/")
  }

  it should "stay as written when it climbs above its root" in {
    remote("/../x") shouldBe List("/../x")
  }

  "a relative local data path" should "stay relative off the local runtime" in {
    remote("./ducklake/tpch") shouldBe List("./ducklake/tpch")
  }

  it should "resolve against this process's directory on the local runtime" in {
    val expected = Paths.get("ducklake/tpch").toAbsolutePath.normalize.toString + "/"
    DataFolders.of("./ducklake/tpch", sameHost = true, identity) shouldBe List(expected)
  }

  "on the local runtime" should "add the real path of a symlinked data path" in {
    val base = Files.createTempDirectory("qod-datafolders")
    try
      val real = Files.createDirectory(base.resolve("real"))
      val link = Files.createSymbolicLink(base.resolve("link"), real)
      val got  = DataFolders.of(s"$link/lake/", sameHost = true, LocalRealPath.of)
      got should contain(s"$link/lake/")
      got should contain(s"${real.toRealPath()}/lake/")
      got.size shouldBe 2
    finally Files.walk(base).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
  }

  "LocalRealPath" should "resolve the deepest existing ancestor and keep the rest and the slash" in {
    val base = Files.createTempDirectory("qod-realpath")
    try
      val real = Files.createDirectory(base.resolve("real"))
      val link = Files.createSymbolicLink(base.resolve("link"), real)
      LocalRealPath.of(s"$link/not/yet/") shouldBe s"${real.toRealPath()}/not/yet/"
      LocalRealPath.of(s"$link/f.parquet") shouldBe s"${real.toRealPath()}/f.parquet"
    finally Files.walk(base).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
  }

  it should "answer an unreadable path unchanged" in {
    LocalRealPath.of("relative/x") shouldBe "relative/x"
  }
