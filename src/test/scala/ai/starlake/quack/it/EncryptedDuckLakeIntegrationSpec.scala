package ai.starlake.quack.it

import ai.starlake.quack.ondemand.DuckLakeInitializer
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.util.{Failure, Success, Try, Using}

/** Proves that an encrypted database actually encrypts what it writes: the Parquet files for
  * `kind=ducklake`, the `.duckdb` file itself for `kind=duckdb-file`.
  *
  * Everything else in this feature asserts about intent: the model carries a flag, the ATTACH
  * carries `ENCRYPTED`, the catalog records `ducklake_metadata.encrypted='true'`, the guard refuses
  * a mismatch. `DuckLakeInitializerRaceSpec` already pins all of that against a live catalog, and
  * this spec deliberately does not repeat any of it.
  *
  * What none of those establish is the only property an operator cares about: that the bytes on
  * disk are unreadable without the catalog. A catalog could record `encrypted='true'` and still
  * write plain Parquet, and every other test in the feature would stay green. So this spec drives
  * data all the way to a file and asserts a reader holding no key cannot open it. The duckdb-file
  * case at the bottom does the same for the other kind, whose coverage was otherwise only string
  * assertions on the SQL the spawn scripts emit.
  *
  * The negative assertion is worthless on its own: a wrong path, an absent extension, or any
  * unrelated error would make it pass. The unencrypted control case at the bottom is what turns it
  * into evidence, by showing the identical read succeeds when the catalog was created without
  * encryption.
  *
  * Cancels (not fails) when Postgres is unreachable or the DuckDB extensions cannot be fetched,
  * matching the sibling specs, so it is inert on a machine that cannot run it.
  */
class EncryptedDuckLakeIntegrationSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodenc")

  Class.forName("org.duckdb.DuckDBDriver")

  /** DuckLake keeps small writes inline in the catalog Postgres rather than writing a data file, so
    * a plain `CREATE TABLE AS` leaves the data path empty. Flushing is what produces the Parquet
    * this spec is about.
    */
  private val FlushInlined = "CALL ducklake_flush_inlined_data('lake')"

  private def duckdbLiteral(v: String): String = "'" + v.replace("'", "''") + "'"

  private def metastoreFor(db: String, dataPath: String): Map[String, String] =
    Map(
      "pgHost"     -> TestPostgres.pgHost,
      "pgPort"     -> TestPostgres.pgPort.toString,
      "pgUser"     -> TestPostgres.pgUser,
      "pgPassword" -> TestPostgres.pgPass,
      "dbName"     -> db,
      "schemaName" -> "main",
      "dataPath"   -> dataPath
    )

  /** Same classification `DuckLakeInitializerRaceSpec` uses: a machine that has never cached the
    * `ducklake` / `postgres` extensions cannot run this at all, and that is a skip. Anything else,
    * notably DuckLake rejecting `ENCRYPTED`, is a real failure and must surface.
    */
  private def extensionUnavailable(t: Throwable): Boolean =
    val msg = Option(t.getMessage).getOrElse("").toLowerCase
    msg.contains("failed to download extension") ||
    msg.contains("unable to connect to extension repository") ||
    (msg.contains("extension") && msg.contains("not found"))

  private def orSkip[A](body: => A): A =
    Try(body) match
      case Success(a)                            => a
      case Failure(t) if extensionUnavailable(t) =>
        cancel(s"DuckDB extensions unavailable (${t.getMessage}); skipping")
      case Failure(t) => throw t

  /** A fresh Postgres catalog database plus a fresh data directory, both reaped afterwards. */
  private def withFreshCatalog(test: (String, Path) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName   = s"qodenc_test_${System.nanoTime()}"
    val dataPath = Files.createTempDirectory(s"$dbName-")
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try test(dbName, dataPath)
    finally
      Try(TestPostgres.dropDatabase(dbName))
      Try(
        Using.resource(Files.walk(dataPath)) { s =>
          s.sorted(java.util.Comparator.reverseOrder[Path]())
            .forEach(p => Files.deleteIfExists(p))
        }
      )

  /** Attaches the catalog the way a spawned node does and writes one row, then flushes it out of
    * the catalog into a Parquet file. The catalog itself was already created by
    * `DuckLakeInitializer`, which is the production code path under test; this session only
    * produces data inside it.
    */
  private def writeOneRow(db: String, dataPath: Path, encrypted: Boolean): Unit =
    val connstr =
      s"ducklake:postgres:host='${TestPostgres.pgHost}' port='${TestPostgres.pgPort}' " +
        s"dbname='$db' user='${TestPostgres.pgUser}' password='${TestPostgres.pgPass}'"
    val opts =
      if encrypted then s"DATA_PATH ${duckdbLiteral(dataPath.toString)}, ENCRYPTED"
      else s"DATA_PATH ${duckdbLiteral(dataPath.toString)}"
    withDuckdb { st =>
      st.execute("INSTALL ducklake; LOAD ducklake;")
      st.execute("INSTALL postgres; LOAD postgres;")
      st.execute(s"ATTACH ${duckdbLiteral(connstr)} AS lake ($opts)")
      st.execute("USE lake")
      st.execute("CREATE TABLE secrets AS SELECT 42 AS n, 'top-secret' AS s")
      st.execute(FlushInlined)
    }

  private def withDuckdb[A](body: java.sql.Statement => A): A =
    val conn: Connection = DriverManager.getConnection("jdbc:duckdb:")
    try
      val st = conn.createStatement()
      try body(st)
      finally st.close()
    finally conn.close()

  /** The first data file under the path, in sorted order so the choice is deterministic. One row
    * flushed once yields exactly one file today; taking the first rather than insisting on it keeps
    * the test about encryption instead of about DuckLake's file-per-flush behaviour.
    */
  private def firstParquetUnder(dataPath: Path): Path =
    val found = Using.resource(Files.walk(dataPath)) { s =>
      s.filter(p => p.toString.endsWith(".parquet")).sorted().toArray(new Array[Path](_)).toList
    }
    withClue(s"no Parquet file was written under $dataPath; ") {
      found should not be empty
    }
    found.head

  /** Reads the file with a DuckDB session that has nothing attached and holds no key: exactly what
    * someone with filesystem or bucket access, but no control-plane credentials, would have.
    * Returns the error message rather than asserting, because one caller expects a failure and
    * needs to show what it was.
    */
  private def bareRead(parquet: Path): Either[String, String] =
    Try {
      withDuckdb { st =>
        val rs = st.executeQuery(s"SELECT s FROM read_parquet(${duckdbLiteral(parquet.toString)})")
        try if rs.next() then rs.getString(1) else ""
        finally rs.close()
      }
    }.toEither.left.map(t => Option(t.getMessage).getOrElse(t.toString))

  /** The first four bytes of a Parquet file: `PAR1` normally, `PARE` when the footer is encrypted.
    * Reported rather than asserted, since it is a detail of Parquet's encrypted-footer mode and the
    * read attempt above is the assertion that matters.
    */
  private def magic(parquet: Path): String =
    new String(Files.readAllBytes(parquet).take(4).toArray, "US-ASCII")

  "an encrypted DuckLake database" should "write Parquet a reader without the catalog cannot open" in
    withFreshCatalog { (db, dataPath) =>
      orSkip(
        DuckLakeInitializer.initBlocking(metastoreFor(db, dataPath.toString), encrypted = true)
      )
      orSkip(writeOneRow(db, dataPath, encrypted = true))

      val parquet = firstParquetUnder(dataPath)
      info(s"encrypted data file magic bytes: ${magic(parquet)}")

      bareRead(parquet) match
        case Left(err) =>
          info(s"a keyless reader was refused with: $err")
          // Not just "the read failed": it failed BECAUSE the file is encrypted. The control case
          // below already rules out a wrong path or a missing extension, and this pins the reason
          // so a future unrelated read failure cannot masquerade as proof of encryption.
          withClue(s"the read failed for a reason other than encryption: $err; ") {
            err.toLowerCase should include("encrypted")
          }
        case Right(v) =>
          fail(
            s"a reader holding no key read '$v' out of $parquet: the data is not encrypted at rest"
          )
    }

  /** The control. Without it the assertion above proves nothing, because a bad path, a missing
    * extension or any unrelated error would fail the read just as convincingly.
    */
  "an unencrypted DuckLake database" should "write Parquet the same reader opens fine" in
    withFreshCatalog { (db, dataPath) =>
      orSkip(
        DuckLakeInitializer.initBlocking(metastoreFor(db, dataPath.toString), encrypted = false)
      )
      orSkip(writeOneRow(db, dataPath, encrypted = false))

      val parquet = firstParquetUnder(dataPath)
      info(s"unencrypted data file magic bytes: ${magic(parquet)}")

      bareRead(parquet) match
        case Right(v)  => v shouldBe "top-secret"
        case Left(err) =>
          fail(
            s"the control case failed to read $parquet ($err), so the encrypted case above " +
              "measures something other than encryption"
          )
    }

  // ---------- kind=duckdb-file: the other half of the feature ----------

  /** Attaches a `.duckdb` file the way `spawn-quack-node.sh` does, with or without a key, and runs
    * `body` against it. `INSTALL httpfs` mirrors the spawn script: DuckDB needs OpenSSL for a
    * WRITABLE encrypted database, and in its mbedtls fallback writes are refused outright.
    */
  private def withAttachedFile[A](file: Path, key: Option[String])(
      body: java.sql.Statement => A
  ): A =
    withDuckdb { st =>
      st.execute("INSTALL httpfs; LOAD httpfs;")
      val opts = key.fold("")(k => s" (ENCRYPTION_KEY ${duckdbLiteral(k)})")
      st.execute(s"ATTACH ${duckdbLiteral(file.toString)} AS db$opts")
      try body(st)
      finally st.execute("DETACH db")
    }

  private def writeFile(file: Path, key: Option[String]): Unit =
    withAttachedFile(file, key)(_.execute("CREATE TABLE db.secrets AS SELECT 'top-secret' AS s"))

  /** Reads the row back through a fresh session, returning the error rather than asserting: the
    * keyless case expects a failure and has to show what it was.
    */
  private def readFile(file: Path, key: Option[String]): Either[String, String] =
    Try {
      withAttachedFile(file, key) { st =>
        val rs = st.executeQuery("SELECT s FROM db.secrets")
        try if rs.next() then rs.getString(1) else ""
        finally rs.close()
      }
    }.toEither.left.map(t => Option(t.getMessage).getOrElse(t.toString))

  /** The first 16 bytes, non-printables dotted. Reported, not asserted: an encrypted DuckDB file
    * still carries the plain `DUCK` magic just past its 8-byte checksum (observed: `....u...DUCKD`
    * for an encrypted file against `..o'....DUCK@` for a plain one), because the header is what
    * tells a reader the database is encrypted in the first place. Only the blocks are ciphertext,
    * which is why the assertion below scans the whole file for the row instead.
    */
  private def head(file: Path): String =
    new String(
      Files.readAllBytes(file).take(16).map(b => if b >= 0x20 && b < 0x7f then b else '.'.toByte),
      "US-ASCII"
    )

  /** Whether the file contains `needle` as raw bytes anywhere. ISO-8859-1 maps bytes one to one, so
    * this is a byte scan, not a text decode.
    */
  private def containsBytes(file: Path, needle: String): Boolean =
    new String(Files.readAllBytes(file), "ISO-8859-1").contains(needle)

  "an encrypted duckdb-file database" should "refuse a keyless reader and keep its rows off disk" in {
    val dir       = Files.createTempDirectory("qodencfile-")
    val encrypted = dir.resolve("enc.duckdb")
    val plain     = dir.resolve("plain.duckdb")
    try
      orSkip(writeFile(encrypted, Some("hunter2")))
      orSkip(writeFile(plain, None))

      info(s"encrypted file header: ${head(encrypted)}")
      info(s"unencrypted file header: ${head(plain)}")

      // The control, same shape as the DuckLake case above: without it, a bad path or a missing
      // extension would fail the keyless read just as convincingly as encryption does.
      readFile(plain, None) match
        case Right(v)  => v shouldBe "top-secret"
        case Left(err) =>
          fail(
            s"the control case failed to read the unencrypted $plain ($err), so the encrypted " +
              "case below measures something other than encryption"
          )
      // And the file itself is intact: the right key still opens it, so the refusal below is
      // about the key and not about a corrupt write.
      readFile(encrypted, Some("hunter2")) shouldBe Right("top-secret")

      readFile(encrypted, None) match
        case Left(err) =>
          info(s"a keyless reader was refused with: $err")
          withClue(s"the attach failed for a reason other than encryption: $err; ") {
            err.toLowerCase should include("encrypt")
          }
        case Right(v) =>
          fail(s"a reader holding no key read '$v' out of $encrypted: the file is not encrypted")

      // The at-rest property itself, one level below the engine: the row is legible in the bytes
      // of the unencrypted file (the control, which is what makes the negative mean something) and
      // absent from the encrypted one.
      withClue(s"the control file $plain does not contain the row in clear; ") {
        containsBytes(plain, "top-secret") shouldBe true
      }
      withClue(s"the encrypted file $encrypted contains the row in clear; ") {
        containsBytes(encrypted, "top-secret") shouldBe false
      }
    finally
      // Walk rather than delete the two names: DuckDB leaves a `.wal` beside each file.
      Try(
        Using.resource(Files.walk(dir)) { s =>
          s.sorted(java.util.Comparator.reverseOrder[Path]())
            .forEach(p => Files.deleteIfExists(p))
        }
      )
  }
