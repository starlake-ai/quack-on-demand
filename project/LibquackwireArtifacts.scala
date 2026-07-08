import sbt._
import sbt.Keys._
import sbt.io.IO

/** Packages the four pre-built `libquackwire.{so,dylib}` native binaries
  * into classifier-per-platform jars for Maven Central publishing.
  *
  * Each binary lives at
  * `libquackwire/binaries/<platform>/libquackwire.<so|dylib>`. CI
  * populates that directory by downloading the 4 matrix artifacts from
  * `.github/workflows/quackwire.yml`'s build job. Local dev populates it
  * by copying the host platform's CMake output:
  *
  * {{{
  * cmake --build native/quackwire/build
  * mkdir -p libquackwire/binaries/osx-aarch64
  * cp native/quackwire/build/libquackwire.dylib libquackwire/binaries/osx-aarch64/
  * sbt libquackwire/publishLocal
  * }}}
  *
  * Per-platform jar layout (what consumers see on the classpath):
  *
  * {{{
  *   native/linux-x86_64/libquackwire.so
  * }}}
  *
  * `QuackNativeBridge.loaded` looks up the matching binary via
  * `getResourceAsStream("/native/<host-platform>/libquackwire.<ext>")`.
  *
  * If a platform binary is absent at task time, the corresponding
  * classifier task fails. CI publishes the whole bundle atomically;
  * partial bundles never make it to Central.
  */
object LibquackwireArtifacts {

  private final case class PlatformSpec(
    classifier: String,
    ext: String,
    libFileName: Option[String] = None
  ) {
    // Windows uses the un-prefixed `quackwire.dll` (System.mapLibraryName has
    // no "lib" prefix there); Unix keeps `libquackwire.{so,dylib}`.
    def libFile: String = libFileName.getOrElse(s"libquackwire.$ext")
  }

  /** Opt-in flag for the Windows native pipeline. Default OFF keeps every
    * existing publish / publishLocal byte-identical (4 Unix classifiers only).
    * CI's Windows build job and Windows devs set `QOD_WITH_WINDOWS_NATIVE=true`
    * to also package `native/windows-x86_64/quackwire.dll`.
    */
  private val withWindows: Boolean =
    sys.env.get("QOD_WITH_WINDOWS_NATIVE").exists(v => v == "true" || v == "1")

  private val Platforms = Seq(
    PlatformSpec("linux-x86_64",  "so"),
    PlatformSpec("linux-aarch64", "so"),
    PlatformSpec("osx-x86_64",    "dylib"),
    PlatformSpec("osx-aarch64",   "dylib")
  ) ++ (
    if (withWindows) Seq(PlatformSpec("windows-x86_64", "dll", Some("quackwire.dll")))
    else Seq.empty
  )

  /** Settings the `libquackwire` subproject mixes in. Returns one
    * `addArtifact(...)` entry per platform plus a packaging task per
    * classifier.
    */
  def classifierSettings: Seq[Setting[_]] = Platforms.flatMap { platform =>
    val classifier = platform.classifier
    val taskKey    = TaskKey[File](s"package-libquackwire-$classifier")
    val artifact   = Artifact("libquackwire", "jar", "jar", classifier)
    Seq(
      taskKey := {
        val log         = streams.value.log
        val binariesDir = baseDirectory.value / "binaries" / classifier
        val sourceBin   = binariesDir / platform.libFile
        if (!sourceBin.isFile) {
          sys.error(
            s"libquackwire[$classifier] missing: $sourceBin\n" +
            s"  Stage the binary at libquackwire/binaries/$classifier/${platform.libFile} " +
            s"before running publishSigned / publishLocal."
          )
        }
        val outJar = target.value / "libquackwire-jars" /
          s"libquackwire-${(libquackwire / version).value}-$classifier.jar"
        IO.createDirectory(outJar.getParentFile)
        IO.zip(
          Seq(sourceBin -> s"native/$classifier/${platform.libFile}"),
          outJar,
          time = None
        )
        log.info(
          s"libquackwire[$classifier] packaged ${sourceBin.length()} bytes -> $outJar"
        )
        outJar
      }
    ) ++ addArtifact(artifact, taskKey).settings
  }

  /** Resolved at use-site by the build.sbt subproject definition. We
    * declare it here so `(libquackwire / version).value` above type-
    * checks even before the `libquackwire` lazy val is in scope.
    */
  private lazy val libquackwire = LocalProject("libquackwire")
}