package ai.starlake.quack.it

import ai.starlake.quack.ondemand.DuckLakeInitializer
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.util.{Failure, Success, Try, Using}

/** Proves that an encrypted DuckLake database actually encrypts its data files.
  *
  * Everything else in this feature asserts about intent: the model carries a flag, the ATTACH
  * carries `ENCRYPTED`, the catalog records `ducklake_metadata.encrypted='true'`, the guard refuses
  * a mismatch. `DuckLakeInitializerRaceSpec` already pins all of that against a live catalog, and
  * this spec deliberately does not repeat any of it.
  *
  * What none of those establish is the only property an operator cares about: that the bytes on
  * disk are unreadable without the catalog. A catalog could record `encrypted='true'` and still
  * write plain Parquet, and every other test in the feature would stay green. So this spec drives
  * data all the way to a Parquet file and asserts a reader holding no key cannot open it.
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
