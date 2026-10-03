package ai.starlake.quack.edge.rest

import ai.starlake.quack.model.SqlLiterals
import ai.starlake.quack.ondemand.InProcessDuckDb
import cats.effect.{Fiber, IO, Outcome, Poll}
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.c.{ArrowArrayStream, Data}
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.DuckDBConnection

import java.io.{FileOutputStream, InputStream}
import java.nio.channels.{Channels, FileChannel}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.{DriverManager, Statement}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.util.Try

/** Parquet (`application/vnd.apache.parquet`) for `/rows`, streamed with bounded memory and no
  * dependency the manager does not already ship.
  *
  * Parquet is written by an in-process DuckDB (the `duckdb_jdbc` the manager already embeds, whose
  * Parquet writer is built in): the capped Arrow result is exported over the Arrow C stream
  * interface (`arrow-c-data`, already on the classpath) and registered as a scan, and `COPY ... TO`
  * writes it into a named pipe whose read end IS the HTTP body. Nothing touches the disk: the pipe
  * is a kernel buffer, and DuckDB holds at most one row group plus the Arrow batch being scanned,
  * whatever the page size. DuckDB writes the file front to back with no seeks (`USE_TMP_FILE false`
  * keeps it from writing elsewhere and renaming over the pipe).
  *
  * Row groups are bounded by rows, sized from the first batch so that one holds about
  * [[RowGroupTargetBytes]] of Arrow data (at most [[RowGroupRows]] rows): DuckDB's own
  * `ROW_GROUP_SIZE_BYTES` is refused while insertion order is preserved, which an ordered page
  * needs. A row much wider than the first batch's can still outgrow the instance's memory limit;
  * the COPY then fails and the stream aborts.
  *
  * The in-process instance is locked down before the scan: one thread, a memory limit
  * ([[MemoryLimit]], outside the JVM heap), no extension install or autoload, external access off
  * with only the pipe's private directory allowed, and the configuration locked. The only SQL it
  * runs is fixed text naming the pipe. The edge bounds how many run at once
  * (`maxConcurrentParquet`, and one per principal).
  *
  * A named pipe needs a POSIX host ([[RestParquetWriter.available]]); elsewhere (Windows) Parquet
  * is not offered and asking for it is the usual 406.
  *
  * Lifecycle: [[RestParquetWriter.open]] pulls the first Arrow batch and the first Parquet bytes,
  * so a failure in either is an ordinary error response; [[rest]] streams the remainder and fails
  * (an aborted connection) when the COPY fails after the first byte; [[abort]] makes whatever the
  * write is blocked on return (a deadline, a cancel); [[close]] tears everything down once, in an
  * order that never releases the Arrow result while DuckDB may still be reading it.
  */
final class RestParquetWriter private (
    val capped: CappedArrowReader,
    parts: RestParquetWriter.Parts,
    val first: Array[Byte]
):

  /** The private directory holding the pipe; gone once [[close]] has run. */
  private[rest] def pipeDir: Path = parts.dir

  /** Rows written (final once [[rest]] completed). */
  def rows: Long = capped.rows

  /** Whether rows existed past what was written: past the row cap, or cut by the byte cap (final
    * once [[rest]] completed).
    */
  def more: Boolean = capped.more || capped.cut

  /** Whether the byte cap ended the rows while some were left (final once [[rest]] completed). */
  def cut: Boolean = capped.cut

  /** Everything after [[first]]; raises when the COPY did not complete. Each pipe read is forcible
    * ([[ForcedReads]]): a cancel aborts the write ([[abort]]) instead of waiting for it.
    */
  def rest: fs2.Stream[IO, Byte] =
    fs2.Stream
      .repeatEval(IO.uncancelable(poll => parts.reads(poll)(parts.readChunk())))
      .unNoneTerminate
      .flatMap(b => fs2.Stream.chunk(fs2.Chunk.array(b))) ++
      fs2.Stream.exec(parts.copied)

  /** Makes everything the write may be blocked on return, without waiting: the node result is
    * closed, the pipe's read end too (a COPY writing to it fails), the COPY cancelled and its
    * thread and any pipe read interrupted. [[close]] still has to run.
    */
  def abort: IO[Unit] = parts.reads.abort

  /** Ends the COPY if it still runs (the pipe's read end closes, so its next write fails, and the
    * statement is cancelled), waits for it, then releases the scan, the instance and the pipe. The
    * capped reader is closed last; the query result itself stays with its owner.
    */
  def close(): IO[Unit] = parts.teardown

object RestParquetWriter extends LazyLogging:

  val ContentType = "application/vnd.apache.parquet"

  /** The most rows a Parquet row group holds. */
  val RowGroupRows = 16384

  /** The Arrow data a row group is sized to hold, from the first batch's bytes per row. */
  val RowGroupTargetBytes: Long = 8L * 1024 * 1024

  /** The in-process instance's memory limit. */
  val MemoryLimit = "64MB"

  /** Rows per row group for a page whose first batch held `bytes` of Arrow data in `rows` rows. */
  private[rest] def rowGroupRows(bytes: Long, rows: Long): Int =
    if rows <= 0 || bytes <= 0 then RowGroupRows
    else math.max(1L, math.min(RowGroupRows.toLong, RowGroupTargetBytes * rows / bytes)).toInt

  private val ChunkBytes = 64 * 1024
  private val PipeName   = "rows.parquet"
  private val TableName  = "rest_rows"

  /** Whether this host can stream Parquet: a POSIX file system and a working `mkfifo`. Probed once.
    */
  lazy val available: Boolean =
    !System.getProperty("os.name", "").toLowerCase.contains("win") &&
      Try {
        val dir = privateDir()
        try mkfifo(dir.resolve(PipeName))
        finally
          Files.deleteIfExists(dir.resolve(PipeName))
          Files.deleteIfExists(dir)
      }.isSuccess

  private def privateDir(): Path =
    Files.createTempDirectory(
      "qod-rest-parquet-",
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    )

  private def mkfifo(path: Path): Unit =
    val p =
      new ProcessBuilder("mkfifo", "-m", "600", path.toString).redirectErrorStream(true).start()
    p.getInputStream.readAllBytes()
    if !p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0 then
      throw new IllegalStateException("mkfifo failed")

  private def succeeded(o: Outcome[IO, Throwable, Unit]): IO[Unit] = o match
    case Outcome.Succeeded(_) => IO.unit
    case Outcome.Errored(t)   => IO.raiseError(t)
    case Outcome.Canceled()   => IO.raiseError(new IllegalStateException("parquet write cancelled"))

  /** Starts writing at most `maxRows` rows of `reader` as Parquet and returns the writer with its
    * first bytes in hand. On failure everything acquired so far is released, the reader excepted.
    *
    * `maxBytes` is the response byte cap, applied to the Arrow data fed to the writer, between
    * batches ([[CappedArrowReader]]). Once it is reached with rows left, the capped reader fails
    * the scan, so the COPY fails before writing the footer and [[rest]] raises: the body is
    * aborted, never a whole file that would pass for a complete page. When that happens before the
    * first byte, the open itself raises [[CappedArrowReader.ByteCapReached]]. Parquet's encoding
    * and compression usually make the body smaller than that data; a column that compresses badly
    * can make it somewhat larger.
    *
    * Uncancelable; [[opening]] is the open a caller can cancel or bound.
    */
  def open(
      reader: ArrowReader,
      maxRows: Int,
      maxBytes: Long = Long.MaxValue
  ): IO[RestParquetWriter] =
    IO.uncancelable(_ => opening(reader, maxRows, maxBytes, IO.unit, NoPoll))

  /** [[open]], run inside the caller's `IO.uncancelable`, whose `poll` it gets. The open waits on
    * the node (the first batch) and on DuckDB (the first bytes, which need a whole row group): each
    * wait goes through `poll` as a forcible read ([[ForcedReads]]), so a cancel, or a `poll` that
    * bounds the wait ([[ForcedReads.within]]), aborts the write, `kill` (the node result's close)
    * included. On any failure, cancellation included, everything acquired so far is released in
    * [[Parts.teardown]]'s order after that abort, the reader excepted.
    *
    * `copyStarted` runs right after the COPY has started (a seam for specs).
    */
  private[rest] def opening(
      reader: ArrowReader,
      maxRows: Int,
      maxBytes: Long,
      kill: IO[Unit],
      poll: Poll[IO],
      copyStarted: Path => IO[Unit] = _ => IO.unit
  ): IO[RestParquetWriter] =
    IO(CappedArrowReader(reader, maxRows, maxBytes)).flatMap { capped =>
      val parts  = new Parts(capped, kill)
      val opened =
        for
          // The first batch, pulled here: a node failing on it is an ordinary error response.
          _ <- parts.reads(poll)(capped.prefetch(): Unit)
          _ <- IO.blocking(parts.setUp())
          _ <- parts.startCopy
          _ <- copyStarted(parts.dir)
          _ <- poll(parts.opener.joinWithNever)
            .onError(_ => parts.reads.abort)
            .onCancel(parts.reads.abort)
          // Whatever the pipe holds first (at least the file's magic), not a full chunk.
          first <- parts.reads(poll)(parts.readChunk().getOrElse(Array.emptyByteArray))
          // No byte at all: the COPY failed before writing anything.
          _ <-
            if first.nonEmpty then IO.unit
            else
              parts.copied *>
                IO.raiseError(new IllegalStateException("parquet writer produced no output"))
        yield new RestParquetWriter(capped, parts, first)
      opened
        .guaranteeCase {
          case Outcome.Succeeded(_) => IO.unit
          case _                    => parts.reads.abort *> parts.teardown
        }
        // At the byte cap, say so.
        .adaptError { case _ if capped.cut => new CappedArrowReader.ByteCapReached }
    }

  /** A `poll` that lets nothing through: the open stays uncancelable. */
  private val NoPoll: Poll[IO] = new Poll[IO]:
    def apply[A](fa: IO[A]): IO[A] = fa

  /** Everything one write holds, filled in as the open proceeds, and the two ways out: [[abort]]
    * (through [[reads]]) forces what may block, [[teardown]] releases in order.
    */
  private[rest] final class Parts(capped: CappedArrowReader, kill: IO[Unit]):
    @volatile var dir: Path                                = null
    @volatile private var pipe: Path                       = null
    @volatile private var conn: DuckDBConnection           = null
    @volatile private var stmt: Statement                  = null
    @volatile private var stream: ArrowArrayStream         = null
    @volatile private var copy: Fiber[IO, Throwable, Unit] = null
    @volatile var opener: Fiber[IO, Throwable, Unit]       = null
    @volatile private var in: InputStream                  = null
    private val inOpened                                   = new AtomicBoolean(false)
    private val closed                                     = new AtomicBoolean(false)

    /** The node read (the first batch) and the pipe reads; forcing one forces the whole write. */
    val reads: ForcedReads = new ForcedReads(
      kill.attempt *> IO.blocking {
        closeIn()
        Option(stmt).foreach(s => Try(s.cancel()))
      } *> IO.defer(Option(copy).fold(IO.unit)(_.cancel.start.void))
    )

    /** The pipe's private directory, the pipe, and the locked-down instance scanning `capped`. */
    def setUp(): Unit =
      dir = privateDir()
      pipe = dir.resolve(PipeName)
      mkfifo(pipe)
      Class.forName("org.duckdb.DuckDBDriver")
      conn = DriverManager
        .getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
        .asInstanceOf[DuckDBConnection]
      stmt = conn.createStatement()
      List(
        "SET threads = 1",
        s"SET memory_limit = '$MemoryLimit'",
        "SET autoinstall_known_extensions = false",
        "SET autoload_known_extensions = false",
        s"SET allowed_directories = [${SqlLiterals.duckdbLiteral(dir.toString + "/")}]",
        "SET enable_external_access = false",
        "SET lock_configuration = true"
      ).foreach(stmt.execute)
      val alloc = capped.getVectorSchemaRoot.getFieldVectors.get(0).getAllocator
      stream = ArrowArrayStream.allocateNew(alloc)
      Data.exportArrayStream(alloc, capped, stream)
      conn.registerArrowStream(TableName, stream)

    /** Starts the COPY (interruptible: with one thread, DuckDB scans on the thread that runs it),
      * and the pipe's read end opening, which waits for the COPY to open the write end. A COPY that
      * fails before it opens the pipe would leave that wait blocked: the write end is then opened
      * (and closed) here, so the read end sees an empty pipe.
      */
    def startCopy: IO[Unit] =
      val sql =
        s"COPY (SELECT * FROM $TableName) TO ${SqlLiterals.duckdbLiteral(pipe.toString)} " +
          s"(FORMAT parquet, USE_TMP_FILE false, " +
          s"ROW_GROUP_SIZE ${rowGroupRows(capped.bytes, capped.rows)})"
      for
        c <- IO.interruptible(stmt.execute(sql): Unit).start
        _ <- IO { copy = c }
        o <- IO.blocking {
          // A channel, not a FileInputStream: closing it wakes a read blocked on it.
          in = Channels.newInputStream(FileChannel.open(pipe, StandardOpenOption.READ))
          inOpened.set(true)
        }.start
        _ <- IO { opener = o }
        _ <- c.join.flatMap {
          case Outcome.Succeeded(_) => IO.unit
          case _                    =>
            IO.blocking(if !inOpened.get then new FileOutputStream(pipe.toFile).close())
              .attempt
              .void
        }.start
      yield ()

    /** The next bytes the pipe holds, or None at its end. */
    def readChunk(): Option[Array[Byte]] =
      val buf = new Array[Byte](ChunkBytes)
      val n   = in.read(buf)
      if n < 0 then None else Some(java.util.Arrays.copyOf(buf, n))

    /** The COPY's outcome, raised when it did not complete. */
    def copied: IO[Unit] = copy.join.flatMap(succeeded)

    private def closeIn(): Unit = Option(in).foreach(s => Try(s.close()))

    /** Once, in this order: the COPY is cancelled; once the pipe's read end is open (the COPY opens
      * the write end early, or its failure releases the opening) it closes, so a COPY blocked
      * writing to it fails; the COPY and the last read are waited for; then the statement, the
      * instance, the scan and the pipe go; the capped reader goes last, since DuckDB may scan it
      * until the COPY has ended.
      */
    def teardown: IO[Unit] =
      IO(closed.compareAndSet(false, true)).ifM(
        for
          _ <- IO.blocking(Option(stmt).foreach(s => Try(s.cancel())))
          _ <- Option(opener).fold(IO.unit)(_.join.void)
          _ <- IO.blocking(closeIn())
          _ <- Option(copy).fold(IO.unit)(_.join.void)
          _ <- reads.settled
          _ <- IO.blocking {
            closeIn()
            Option(stmt).foreach(s => Try(s.close()))
            Option(conn).foreach(c => Try(c.close()))
            Option(stream).foreach(s => Try(s.close()))
            Option(pipe).foreach(p => Try(Files.deleteIfExists(p)))
            Option(dir).foreach(d => Try(Files.deleteIfExists(d)))
            Try(capped.close())
          }.void
        yield (),
        IO.unit
      )
