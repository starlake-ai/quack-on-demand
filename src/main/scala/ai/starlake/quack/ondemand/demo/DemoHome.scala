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

  /** System property that pins where the DuckDB / quack JNI native is unpacked (Guardrail 2). */
  val NativeExtractionProp = "duckdb.jni.tmpdir"

  private def defaultRoot: Path =
    val tmp = sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))
    Paths.get(tmp, "qod-demo")

  /** Create the demo home (from `explicit`, else `QOD_DEMO_HOME`, else `${TMPDIR}/qod-demo`) and
    * its subdirs, then pin the native-extraction system property to `nativeDir`.
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
    System.setProperty(NativeExtractionProp, nativeDir.toString)
    DemoHome(root, pgDir, dataPath, nativeDir)
