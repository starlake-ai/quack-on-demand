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
  def deleteRecursively(): Unit =
    if Files.exists(root) then
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(p =>
          try Files.deleteIfExists(p)
          catch { case _: Throwable => () }
        )

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
    List(root, pgDir, dataPath, nativeDir).foreach(Files.createDirectories(_))
    DemoHome(root, pgDir, dataPath, nativeDir)
