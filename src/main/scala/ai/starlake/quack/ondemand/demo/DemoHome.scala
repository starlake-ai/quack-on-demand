package ai.starlake.quack.ondemand.demo

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Ephemeral root for a `qod demo` run. Everything a demo touches lives under `root` so teardown is
  * a single recursive delete.
  */
final case class DemoHome(root: Path, pgDir: Path, dataPath: Path, nativeDir: Path):

  /** Best-effort recursive delete (deepest-first). Teardown must not throw on a partially-created
    * or locked tree.
    */
  def deleteRecursively(): Unit = DemoHome.wipe(root)

object DemoHome:

  // NOTE (final-review Fix 1): a prior revision of this object set a
  // `duckdb.jni.tmpdir` system property here, intending to pin where the DuckDB JNI native gets
  // unpacked (the design doc's "Guardrail 2"). That property is never read by anything -- the
  // DuckDB JDBC driver (`org.duckdb.DuckDBNative.unpackAndLoad`) always unpacks via
  // `Files.createTempFile("libduckdb_java", ".so")`, which resolves against the JVM's
  // `jdk.internal.util.StaticProperty` snapshot of `java.io.tmpdir` taken at VM start -- not the
  // live `System.getProperty`/`System.setProperty` value. Verified empirically (Java 21): calling
  // `System.setProperty("java.io.tmpdir", ...)` at any point *after* the JVM has started, however
  // early in `main`, has zero effect on where `Files.createTempFile` lands; only a `-D` flag passed
  // at JVM launch does. The demo has no way to pass JVM flags to its own already-running process, so
  // this pin is not achievable in-process. Rather than keep an assertion on a property nothing
  // reads, the property-set was removed here; the native `.so` extracts to the ambient
  // `java.io.tmpdir` and self-cleans via `File.deleteOnExit()` inside DuckDB's own unpack code. See
  // `docs/superpowers/specs/2026-07-16-qod-demo-self-contained-design.md` Guardrail 2 for the
  // corrected claim. `nativeDir` is kept as a real, created-and-deleted subdir of the demo home for
  // shape/symmetry with `pgDir`/`dataPath`, even though nothing writes into it today.

  private def defaultRoot: Path =
    val tmp = sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))
    Paths.get(tmp, "qod-demo")

  /** Create the demo home (from `explicit`, else `QOD_DEMO_HOME`, else `${TMPDIR}/qod-demo`) and
    * its subdirs.
    */
  def create(explicit: Option[String]): DemoHome =
    val root = explicit
      .orElse(sys.env.get("QOD_DEMO_HOME"))
      .map(Paths.get(_))
      .getOrElse(defaultRoot)
    val pgDir     = root.resolve("pg")
    val dataPath  = root.resolve("ducklake")
    val nativeDir = root.resolve("native")
    // The clean below is destructive, so refuse it while a previous demo is still LIVE on this
    // home -- two concurrent `qod start --demo` share `${TMPDIR}/qod-demo` by default. Postgres's
    // own `postmaster.pid` names the process holding the data directory; a dead pid is exactly the
    // crashed run the clean exists for, so it falls through.
    // A live postmaster alone does not mean a live demo. A manager killed without reaping its
    // embedded Postgres (SIGKILL, crash, machine sleep) leaves the postmaster running with nobody
    // using it, and that orphan then blocked every later run on this home with no recovery short
    // of finding and killing the pid by hand. So the pair is what decides:
    //   manager alive  -> a real demo holds this home, refuse and name what to stop
    //   manager gone   -> our own orphan, reclaim it, but only once the process proves to be ours
    // Ownership is proven from the process itself (its command line names this home's pgdata),
    // never assumed from the absent manager.pid: an unrelated process that happens to hold a
    // recycled pid must be refused, not killed.
    livePostmaster(pgDir).foreach { pgPid =>
      liveManager(root) match
        case Some(mgrPid) =>
          sys.error(
            s"a demo is already running on $root (manager pid $mgrPid, postgres pid $pgPid) - " +
              s"stop it with `kill $mgrPid`, or give this run its own QOD_DEMO_HOME"
          )
        case None if !ownsPgData(pgPid, pgDir) =>
          sys.error(
            s"a demo is already running on $root (postgres pid $pgPid) - stop it first with " +
              s"`kill $pgPid`, or give this run its own QOD_DEMO_HOME"
          )
        case None => reclaimOrphan(pgPid, root)
    }
    // Teardown normally empties the home, but a run killed before it (SIGKILL, machine sleep, OOM)
    // leaves `pg/pgdata` populated -- and the next run's `initdb` then refuses with `directory
    // "..." exists but is not empty`, which zonky reports only as the opaque
    // `IllegalStateException: Process [...initdb...] failed` (its stderr goes to an INFO logger the
    // default QOD_LOG_LEVEL=ERROR swallows). So every run starts from an empty tree. The clean is
    // scoped to the three subdirs the demo owns, never `root` itself: `QOD_DEMO_HOME` is
    // caller-supplied and may point at a directory holding other things.
    List(pgDir, dataPath, nativeDir).foreach(wipe)
    // `wipe` is best-effort by design (teardown must not throw). Here a silent failure would land
    // right back on the unreadable initdb error, so check the one path that matters and say what
    // to remove.
    val stalePgData = pgDir.resolve("pgdata")
    if Files.exists(stalePgData) then
      sys.error(
        s"demo home $root still holds a previous run's Postgres data directory at $stalePgData " +
          "and it could not be removed - delete it by hand, or point QOD_DEMO_HOME elsewhere"
      )
    List(root, pgDir, dataPath, nativeDir).foreach(Files.createDirectories(_))
    // Written in `root`, which the wipe above deliberately never touches, so it outlives the
    // owned subdirs and is what the next run reads to tell a live demo from an orphan. Recorded
    // BEFORE Postgres starts: a concurrent run must see this pid and back off rather than find a
    // postmaster with no manager and mistake a starting demo for a dead one.
    Files.writeString(managerPidFile(root), s"${ProcessHandle.current().pid()}\n")
    DemoHome(root, pgDir, dataPath, nativeDir)

  /** The pid from `pgdata/postmaster.pid` when that process is still alive, else `None` (no file,
    * unreadable, unparseable, or a pid that has since died).
    */
  private def livePostmaster(pgDir: Path): Option[Long] =
    val pidFile = pgDir.resolve("pgdata").resolve("postmaster.pid")
    if !Files.exists(pidFile) then None
    else
      scala.util
        .Try(Files.readString(pidFile).linesIterator.next().trim.toLong)
        .toOption
        .filter(pid => ProcessHandle.of(pid).filter(_.isAlive).isPresent)

  /** Where a run records its own pid, in `root` rather than a wiped subdir so it survives the
    * pre-flight clean and is readable by the next run.
    */
  private def managerPidFile(root: Path): Path = root.resolve("manager.pid")

  /** The pid from `manager.pid` when that process is still alive, else `None`. Same tolerance as
    * [[livePostmaster]]: a missing, unreadable, unparseable or dead pid all read as "no manager".
    */
  private def liveManager(root: Path): Option[Long] =
    val f = managerPidFile(root)
    if !Files.exists(f) then None
    else
      scala.util
        .Try(Files.readString(f).trim.toLong)
        .toOption
        .filter(pid => ProcessHandle.of(pid).filter(_.isAlive).isPresent)

  /** Whether `pid` is a process this demo home owns, proven by its command line naming this home's
    * `pgdata` (Postgres runs as `postgres -D <pgdata>`). Both the absolute and the real path are
    * checked because macOS reports `/var/folders/...` on the command line for a directory whose
    * real path is `/private/var/folders/...`. A process whose command line cannot be read (another
    * user, tightened permissions) is NOT ours as far as this check is concerned, so the caller
    * refuses instead of killing it.
    */
  private def ownsPgData(pid: Long, pgDir: Path): Boolean =
    val pgData = pgDir.resolve("pgdata")
    val names  = Set(
      scala.util.Try(pgData.toAbsolutePath.toString).toOption,
      scala.util.Try(pgData.toRealPath().toString).toOption
    ).flatten.filter(_.nonEmpty)
    val handle = ProcessHandle.of(pid)
    handle.isPresent && {
      val cmd = handle.get().info().commandLine().orElse("")
      names.exists(cmd.contains)
    }

  /** Stop an orphaned postgres this home owns: SIGTERM, then SIGKILL if it will not go, then give
    * up loudly rather than fall through to a destructive wipe of a directory something still holds
    * open.
    */
  private def reclaimOrphan(pid: Long, root: Path): Unit =
    val handle = ProcessHandle.of(pid)
    if handle.isPresent then
      val h = handle.get()
      h.destroy()
      awaitExit(h, 10000)
      if h.isAlive then
        h.destroyForcibly()
        awaitExit(h, 5000)
    if ProcessHandle.of(pid).filter(_.isAlive).isPresent then
      sys.error(
        s"an orphaned postgres (pid $pid) holds $root and would not stop - kill it by hand " +
          s"with `kill -9 $pid`, or give this run its own QOD_DEMO_HOME"
      )

  /** Poll until the process exits or the budget runs out. */
  private def awaitExit(h: ProcessHandle, budgetMs: Long): Unit =
    val deadline = System.currentTimeMillis() + budgetMs
    while h.isAlive && System.currentTimeMillis() < deadline do Thread.sleep(50)

  /** Best-effort recursive delete of one subtree, deepest-first. Never throws: it runs both on the
    * teardown path (which must not mask the real failure) and on the pre-flight clean above.
    */
  private def wipe(dir: Path): Unit =
    if Files.exists(dir) then
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(p =>
          try Files.deleteIfExists(p)
          catch { case _: Throwable => () }
        )
