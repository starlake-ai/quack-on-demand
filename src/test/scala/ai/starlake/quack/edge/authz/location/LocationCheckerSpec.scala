package ai.starlake.quack.edge.authz.location

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocationCheckerSpec extends AnyFlatSpec with Matchers:

  private val Rules = LocationRules(
    tenantBuckets = Set("acme-etl", "shared-lake"),
    tenantFolders =
      List("/srv/qod-files/acme/in/", "/srv/qod-files/acme/out/", "/srv/qod-files/acme/db/"),
    dataFolders = List(
      "s3://shared-lake/acme_tpch-1a2b3c4d/",
      "s3://shared-lake/lake-1/",
      "/srv/qod-files/acme/out/lake/",
      "/srv/qod-files/acme/db/acme.duckdb/",
      "/var/qod/acme_file.duckdb/"
    ),
    filesRoot = Some("/srv/qod-files/")
  )

  private val Windows = LocationRules(
    tenantBuckets = Set.empty,
    tenantFolders = List("C:/qod/files/acme/in/", "C:/qod/files/acme/db/"),
    dataFolders = List("C:/qod/files/acme/db/lake/"),
    filesRoot = Some("C:/qod/files/")
  )

  private def ok(path: String, rules: LocationRules = Rules) =
    withClue(path)(LocationChecker.check(path, rules) shouldBe Right(()))

  private def outside(path: String, rules: LocationRules = Rules) =
    withClue(path)(
      LocationChecker
        .check(path, rules)
        .left
        .map(_.startsWith(s"$path ${LocationChecker.Outside}")) shouldBe Left(true)
    )

  private def dataFolder(path: String, rules: LocationRules = Rules) =
    withClue(path)(
      LocationChecker
        .check(path, rules)
        .left
        .map(_.startsWith(s"$path ${LocationChecker.DataFolder}")) shouldBe Left(true)
    )

  "an s3 URL" should "be admitted anywhere in an authorized bucket outside data folders" in {
    ok("s3://acme-etl/in/2026/x.parquet")
    ok("s3://acme-etl/")
    ok("s3://acme-etl/out/")
    ok("s3://shared-lake/exports/x.csv")
  }

  it should "be refused in any other bucket" in {
    outside("s3://other/x.parquet")
    outside("s3://acme-etl-2/x.parquet")
    outside("s3://acme/x.parquet")
  }

  it should "be refused with an uppercase or malformed bucket, or no slash after it" in {
    outside("s3://ACME-ETL/x.parquet")
    outside("s3://Acme-etl/x.parquet")
    outside("s3://acme-etl")
    outside("s3://ab/x")
    outside("s3://acme..etl/x")
    outside("s3://user@acme-etl/x")
    outside("s3://acme-etl:443/x")
    outside("s3:///x")
  }

  it should "refuse a query string, a fragment, a backslash and a percent-escape" in {
    outside("s3://acme-etl/x.parquet?s3_endpoint=evil.example")
    outside("s3://acme-etl/x.parquet#frag")
    outside("s3://acme-etl/in\\..\\x")
    outside("s3://acme-etl/%2e%2e/x")
    outside("s3://acme-etl/in/x%20y.csv")
  }

  it should "refuse empty, '.' and '..' segments" in {
    outside("s3://acme-etl//x")
    outside("s3://acme-etl/./x")
    outside("s3://acme-etl/in/../x")
    outside("s3://acme-etl/in/..")
    outside("s3://acme-etl/in//")
  }

  it should "refuse every other scheme" in
    List(
      "http://acme-etl.s3.amazonaws.com/x",
      "https://acme-etl.s3.amazonaws.com/x",
      "gs://acme-etl/x",
      "gcs://acme-etl/x",
      "az://acme-etl/x",
      "azure://acme-etl/x",
      "abfss://acme-etl@acct.dfs.core.windows.net/x",
      "r2://acme-etl/x",
      "s3a://acme-etl/x",
      "s3n://acme-etl/x",
      "S3://acme-etl/x",
      "file:///srv/qod-files/acme/in/x.csv",
      "hf://datasets/x"
    ).foreach(outside(_))

  "a data folder" should "be refused itself, below it and as an ancestor" in {
    dataFolder("s3://shared-lake/lake-1/")
    dataFolder("s3://shared-lake/lake-1")
    dataFolder("s3://shared-lake/lake-1/main/t/ducklake-0001.parquet")
    dataFolder("s3://shared-lake/")
    dataFolder("/srv/qod-files/acme/out/lake/main/t/f.parquet")
    dataFolder("/srv/qod-files/acme/out/")
    dataFolder("/srv/qod-files/acme/out/lake")
  }

  it should "compare whole segments" in {
    ok("s3://shared-lake/lake-10/x.parquet")
    ok("s3://shared-lake/lake/x.parquet")
    ok("s3://shared-lake/acme_tpch-1a2b3c4/x.parquet")
    ok("/srv/qod-files/acme/out/lakehouse/x.parquet")
    ok("/srv/qod-files/acme/out/x.parquet")
  }

  it should "cover branch siblings and a database file's derived files" in {
    dataFolder("s3://shared-lake/lake-1__br_0a1b2c3d/x.parquet")
    dataFolder("s3://shared-lake/lake-1__br_/x.parquet")
    dataFolder("/srv/qod-files/acme/db/acme.duckdb.wal")
    dataFolder("/srv/qod-files/acme/db/acme.duckdb.tmp/x")
    dataFolder("/srv/qod-files/acme/db/")
    ok("/srv/qod-files/acme/db/acme.duckdbx")
  }

  it should "match case-insensitively" in {
    dataFolder("s3://shared-lake/LAKE-1/x.parquet")
    dataFolder("/srv/qod-files/acme/out/LAKE/x.parquet")
    dataFolder("/srv/qod-files/acme/out/Lake__BR_0a1b2c3d/x.parquet")
  }

  it should "admit a glob only in a segment past every data-folder match" in {
    ok("s3://shared-lake/exports/*.csv")
    ok("s3://shared-lake/lake-10/**/x.parquet")
    ok("s3://acme-etl/in/**")
    ok("/srv/qod-files/acme/in/*.csv")
    ok("/srv/qod-files/acme/in/**/x.csv")
    dataFolder("s3://shared-lake/*/x.parquet")
    dataFolder("s3://shared-lake/lake-*/x.parquet")
    dataFolder("s3://shared-lake/**")
    dataFolder("s3://shared-lake/lake-1/*.parquet")
    dataFolder("s3://shared-lake/lake-[1]/x.parquet")
    dataFolder("s3://shared-lake/{lake-1,x}/y.parquet")
    dataFolder("/srv/qod-files/acme/out/*/x.parquet")
    dataFolder("/srv/qod-files/acme/out/**")
  }

  it should "deny a whole bucket when the data path is its root or cannot be parsed" in {
    dataFolder("s3://acme-etl/in/x.csv", Rules.copy(dataFolders = List("s3://acme-etl/")))
    dataFolder("s3://acme-etl/in/x.csv", Rules.copy(dataFolders = List("s3://acme-etl")))
    dataFolder("s3://acme-etl/", Rules.copy(dataFolders = List("s3://acme-etl")))
    dataFolder(
      "s3://acme-etl/in/x.csv",
      Rules.copy(dataFolders = List("s3://acme-etl/odd%20dir/"))
    )
    ok("s3://shared-lake/x.csv", Rules.copy(dataFolders = List("s3://acme-etl/odd%20dir/")))
  }

  "a data folder outside the tenant's locations" should "read as outside, never as a data folder" in {
    outside("/var/qod/acme_file.duckdb/")
    outside("/var/qod/acme_file.duckdb.wal")
  }

  "a local path" should "be admitted under a tenant folder" in {
    ok("/srv/qod-files/acme/in/x.csv")
    ok("/srv/qod-files/acme/in/")
    ok("/srv/qod-files/acme/in")
    ok("/srv/qod-files/acme/out/2026/x.parquet")
  }

  it should "be refused outside every tenant folder" in {
    outside("/srv/qod-files/acme/x.csv")
    outside("/srv/qod-files/acme/")
    outside("/srv/qod-files/globex/in/x.csv")
    outside("/srv/qod-files/acme/in2/x.csv")
    outside("/etc/passwd")
    outside("/")
  }

  it should "be refused when no files root is set, naming the setting" in {
    LocationChecker.check("/srv/qod-files/acme/in/x.csv", Rules.copy(filesRoot = None)) shouldBe
      Left(
        "/srv/qod-files/acme/in/x.csv is outside the tenant's authorized locations (no local " +
          "folder can be authorized: QOD_TENANT_FILES_ROOT is unset)"
      )
  }

  it should "be refused under a tenant folder that is not under the files root" in {
    outside("/etc/passwd", Rules.copy(tenantFolders = List("/etc/")))
    outside("/srv/qod-files/acme/in/x.csv", Rules.copy(filesRoot = Some("/srv/other/")))
  }

  it should "refuse a glob inside a tenant folder's own segments" in {
    outside("/srv/qod-files/*/in/x.csv")
    outside("/srv/qod-files/acme/i*/x.csv")
    outside("/srv/qod-files/acme/{in,out}/x.csv")
    outside("/**")
  }

  it should "compare POSIX paths case-sensitively" in {
    outside("/srv/qod-files/acme/IN/x.csv")
    outside("/SRV/qod-files/acme/in/x.csv")
  }

  it should "refuse relative and malformed paths" in
    List(
      "in/x.csv",
      "./in/x.csv",
      "../acme/in/x.csv",
      "~/x.csv",
      "x.csv",
      "",
      "/srv/qod-files/acme/in/../../globex/in/x.csv",
      "/srv/qod-files/acme/in/./x.csv",
      "/srv/qod-files//acme/in/x.csv",
      "//srv/qod-files/acme/in/x.csv",
      "/srv/qod-files/acme/in\\x.csv",
      "/srv/qod-files/acme/in/x%2ecsv"
    ).foreach(outside(_))

  it should "refuse a control character and keep it out of the reason" in {
    LocationChecker.check("/srv/qod-files/acme/in/x\u0000.csv", Rules) shouldBe
      Left(
        "/srv/qod-files/acme/in/x.csv is outside the tenant's authorized locations " +
          "(a control character)"
      )
    LocationChecker.check("s3://acme-etl/x\n.csv", Rules) shouldBe
      Left("s3://acme-etl/x.csv is outside the tenant's authorized locations (a control character)")
  }

  "a relative data path" should "refuse every local location and leave S3 alone" in {
    val rules = Rules.copy(dataFolders = List("ducklake/tpch"))
    dataFolder("/srv/qod-files/acme/in/x.csv", rules)
    LocationChecker.check("/srv/qod-files/acme/in/x.csv", rules) shouldBe
      Left(
        "/srv/qod-files/acme/in/x.csv is a database data folder (a relative data path of this " +
          "deployment may lie there)"
      )
    ok("s3://acme-etl/in/x.csv", rules)
  }

  "a Windows path" should "be admitted under a tenant folder, either separator, any case" in {
    ok("C:\\qod\\files\\acme\\in\\x.csv", Windows)
    ok("C:/qod/files/acme/in/x.csv", Windows)
    ok("c:\\QOD\\Files\\acme\\IN\\x.csv", Windows)
    ok("C:\\qod\\files\\acme\\in\\*.csv", Windows)
  }

  it should "be refused in a data folder whatever the case" in {
    dataFolder("C:\\qod\\files\\acme\\db\\LAKE\\x.parquet", Windows)
    dataFolder("C:\\qod\\files\\acme\\db\\lake", Windows)
    dataFolder("C:\\qod\\files\\acme\\db\\*\\x.parquet", Windows)
    ok("C:\\qod\\files\\acme\\db\\other\\x.parquet", Windows)
  }

  it should "refuse streams, trailing dots and spaces, short names and device names" in
    List(
      "C:\\qod\\files\\acme\\in\\x.csv:stream",
      "C:\\qod\\files\\acme\\in\\lake.\\x.parquet",
      "C:\\qod\\files\\acme\\in\\lake \\x.parquet",
      "C:\\qod\\files\\acme\\in\\LAKE~1\\x.parquet",
      "C:\\qod\\files\\acme\\in\\nul.txt",
      "C:\\qod\\files\\acme\\in\\CON",
      "C:\\qod\\files\\acme\\in\\com1.csv",
      "C:\\qod\\files\\acme\\in\\..\\x.csv",
      "C:\\qod\\files\\acme\\in\\\\x.csv"
    ).foreach(outside(_, Windows))

  it should "refuse UNC, device and drive-relative paths and other drives" in
    List(
      "\\\\server\\share\\x.csv",
      "\\\\?\\C:\\qod\\files\\acme\\in\\x.csv",
      "C:qod\\files\\acme\\in\\x.csv",
      "D:\\qod\\files\\acme\\in\\x.csv",
      "/qod/files/acme/in/x.csv"
    ).foreach(outside(_, Windows))
