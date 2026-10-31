package ai.starlake.quack.edge.authz.location

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocationCheckerSpec extends AnyFlatSpec with Matchers:

  private val Rules = LocationRules(
    s3Scopes = List("s3://acme-etl/", "s3://shared-lake/"),
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
    s3Scopes = Nil,
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

  it should "keep format and separator characters out of the reason" in {
    LocationChecker.check("/etc/x\u202ey\u2028z\u2029\u200b\u2066.csv", Rules) shouldBe
      Left("/etc/xyz.csv is outside the tenant's authorized locations")
  }

  "a data folder named in one Unicode normalization" should "be refused in the other" in {
    dataFolder(
      "/srv/qod-files/acme/out/cafe\u0301/x.parquet",
      Rules.copy(dataFolders = List("/srv/qod-files/acme/out/caf\u00e9/"))
    )
    dataFolder(
      "/srv/qod-files/acme/out/caf\u00e9.wal",
      Rules.copy(dataFolders = List("/srv/qod-files/acme/out/cafe\u0301/"))
    )
    dataFolder(
      "s3://shared-lake/cafe\u0301/x.parquet",
      Rules.copy(dataFolders = List("s3://shared-lake/caf\u00e9/"))
    )
    ok(
      "/srv/qod-files/acme/out/cafe/x.parquet",
      Rules.copy(dataFolders = List("/srv/qod-files/acme/out/caf\u00e9/"))
    )
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

  it should "be refused onto a database configured with backslashes, as qod-node resolves it" in {
    // qod-node's ResolveLocalPath joins with '\' on Windows (a DuckLake flush's COPY target reads
    // C:\...\lake_data\main\t), and an operator may write the data path either way.
    val rules = Windows.copy(dataFolders =
      DataFolders.of("C:\\qod\\files\\acme\\db\\lake2\\", false, identity) ++
        DataFolders.of(
          "C:\\qod\\files\\acme\\db\\x.duckdb" + DataFolders.NamePrefixMarker,
          false,
          identity
        )
    )
    dataFolder("C:\\qod\\files\\acme\\db\\lake2\\main\\t", rules)
    dataFolder("C:/qod/files/acme/db/lake2/main/t", rules)
    dataFolder("C:\\qod\\files\\acme\\db\\x.duckdb", rules)
    dataFolder("C:\\qod\\files\\acme\\db\\x.duckdb.wal", rules)
    dataFolder("c:\\QOD\\files\\acme\\db\\X.DUCKDB2\\f.parquet", rules)
    ok("C:\\qod\\files\\acme\\db\\y.duckdb", rules)
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

  "an s3 scope" should "admit only what lies under it, on whole segments" in {
    val r = Rules.copy(s3Scopes = List("s3://acme-etl/in/"))
    ok("s3://acme-etl/in/x.parquet", r)
    ok("s3://acme-etl/in/", r)
    ok("s3://acme-etl/in/2026/*.parquet", r)
    outside("s3://acme-etl/out/x.parquet", r)
    outside("s3://acme-etl/in-2/x.parquet", r)
    outside("s3://acme-etl/", r)
    outside("s3://acme-etl/i*/x.parquet", r)
    LocationChecker.check("s3://other/x", r) shouldBe
      Left(s"s3://other/x ${LocationChecker.Outside} (${LocationChecker.NoCredential})")
  }

  it should "admit nothing when the user holds none" in
    outside("s3://acme-etl/in/x.parquet", Rules.copy(s3Scopes = Nil))

  "scopeError" should "refuse a scope at or under a data folder or its siblings, and admit an ancestor" in {
    val d = List("s3://shared-lake/lake-1/", "/srv/qod-files/acme/db/lake/")
    List(
      "s3://shared-lake/lake-1/",
      "s3://shared-lake/lake-1/main/",
      "s3://shared-lake/LAKE-1/",
      "s3://shared-lake/lake-1__br_0123abcd/",
      "s3://shared-lake/lake-1.wal/"
    ).foreach { s =>
      withClue(s)(
        LocationChecker.scopeError(s, d) shouldBe Some(s"$s ${LocationChecker.DataFolder}")
      )
    }
    List(
      "s3://shared-lake/",
      "s3://shared-lake/lake-10/",
      "s3://shared-lake/lake-2/",
      "s3://other/"
    )
      .foreach(s => withClue(s)(LocationChecker.scopeError(s, d) shouldBe None))
    LocationChecker.scopeError("s3://Bad/", d).isDefined shouldBe true
  }

  it should "refuse every scope of a bucket whose data path the checker cannot parse" in {
    val d = List("s3://lake/x" + 1.toChar + "y/")
    LocationChecker.scopeError("s3://lake/other/", d).isDefined shouldBe true
    LocationChecker.scopeError("s3://lake2/other/", d) shouldBe None
  }

  "admittedBy" should "name the longest scope or folder that admits a location" in {
    val r = Rules.copy(s3Scopes = List("s3://acme-etl/", "s3://acme-etl/in/"))
    LocationChecker.admittedBy("s3://acme-etl/in/x.parquet", r) shouldBe Some("s3://acme-etl/in/")
    LocationChecker.admittedBy("s3://acme-etl/out/x.parquet", r) shouldBe Some("s3://acme-etl/")
    LocationChecker.admittedBy("/srv/qod-files/acme/in/x.csv", r) shouldBe
      Some("/srv/qod-files/acme/in/")
    LocationChecker.admittedBy("s3://other/x", r) shouldBe None
  }

  "an access point alias" should "be refused at use, whatever scope names it" in
    List(
      "s3://myap-a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7-s3alias/x.parquet",
      "s3://my-olap-a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5--ol-s3/x.parquet",
      "s3://mfzwi23gnjvgw.mrap/x.parquet"
    ).foreach { url =>
      val bucket = url.stripPrefix("s3://").takeWhile(_ != '/')
      val r      = Rules.copy(s3Scopes = List(s"s3://$bucket/"))
      withClue(url)(
        LocationChecker.check(url, r) shouldBe
          Left(s"$url ${LocationChecker.Outside} (an access point alias, not a bucket)")
      )
    }

  "a data path the manager cannot place" should "refuse every local location, saying so" in {
    val rules = Rules.copy(dataFolders = List(DataFolders.Unplaced))
    LocationChecker.check("/srv/qod-files/acme/in/x.csv", rules) shouldBe
      Left(
        "/srv/qod-files/acme/in/x.csv is a database data folder (a database whose data path " +
          "this manager cannot place may lie there)"
      )
    ok("s3://acme-etl/in/x.csv", rules)
  }

  "a reason" should "keep supplementary-plane format characters out, tag characters included" in {
    // U+E0001 (LANGUAGE TAG), U+E0041 (TAG LATIN CAPITAL LETTER A), U+E007F (CANCEL TAG) and
    // U+1D173 (MUSICAL SYMBOL BEGIN BEAM), each a surrogate pair.
    LocationChecker.check(
      "/etc/x\uDB40\uDC01y\uDB40\uDC41z\uDB40\uDC7F\uD834\uDD73.csv",
      Rules
    ) shouldBe
      Left("/etc/xyz.csv is outside the tenant's authorized locations")
    val tagged = "s3://shared-lake/lake-1\uDB40\uDC41/"
    LocationChecker.scopeError(tagged, List(tagged)) shouldBe
      Some(s"s3://shared-lake/lake-1/ ${LocationChecker.DataFolder}")
  }

  "a database file" should "deny every name beginning with its own, which its secret would sign" in {
    val file =
      DataFolders.of("s3://shared-lake/x.duckdb" + DataFolders.NamePrefixMarker, false, identity)
    val rules = Rules.copy(dataFolders = file)
    dataFolder("s3://shared-lake/x.duckdb", rules)
    dataFolder("s3://shared-lake/x.duckdb.wal", rules)
    dataFolder("s3://shared-lake/x.duckdb2/f.parquet", rules)
    dataFolder("s3://shared-lake/X.DUCKDB2/f.parquet", rules)
    dataFolder("s3://shared-lake/x.d*/f.parquet", rules)
    ok("s3://shared-lake/x.duck/f.parquet", rules)
    ok("s3://shared-lake/y.duckdb/f.parquet", rules)
    LocationChecker.scopeError("s3://shared-lake/x.duckdb2/", file) shouldBe
      Some(s"s3://shared-lake/x.duckdb2/ ${LocationChecker.DataFolder}")
    LocationChecker.scopeError("s3://shared-lake/", file) shouldBe None
    // A local file too, under its tenant folder.
    val local = Rules.copy(dataFolders =
      DataFolders.of(
        "/srv/qod-files/acme/in/f.duckdb" + DataFolders.NamePrefixMarker,
        false,
        identity
      )
    )
    dataFolder("/srv/qod-files/acme/in/f.duckdb2/x.csv", local)
    ok("/srv/qod-files/acme/in/g.csv", local)
  }

  "the unplaced reason" should "be the one the supervisor's WARN names" in {
    LocationChecker.check(
      "/srv/qod-files/acme/in/x.csv",
      Rules.copy(dataFolders = List(DataFolders.Unplaced))
    ) shouldBe
      Left(
        s"/srv/qod-files/acme/in/x.csv ${LocationChecker.DataFolder} (${DataFolders.UnplacedReason})"
      )
  }
