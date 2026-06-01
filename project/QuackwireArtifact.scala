import sbt._
import sbt.Keys._

import java.io.IOException
import java.net.{HttpURLConnection, URL}

/** sbt-side counterpart to `.github/workflows/quackwire.yml`.
  *
  * `fetchQuackwire` downloads the four per-platform `libquackwire`
  * binaries published by the workflow and writes them into
  * `Compile / resourceManaged / native/<platform>/`. They are then picked
  * up by `resourceGenerators`, copied into `Compile / classDirectory` by
  * `copyResources`, and packaged into the assembly jar - matching the
  * runtime resource lookup `QuackNativeBridge.loaded` performs via
  * `getResourceAsStream("/native/<platform>/libquackwire.{so,dylib}")`.
  *
  * Design decisions:
  *
  * - **Output goes to `resourceManaged`, not `src/main/resources/native/`.**
  *   Keeps the source tree clean so `.gitignore` can leave the resources
  *   subtree unignored, and so a `git status` after `sbt compile` shows
  *   nothing new. Task 1.5 of the JNI plan staged binaries under
  *   `src/main/resources/native/` as a placeholder - that exclusion is
  *   removed from `.gitignore` once this task lands.
  *
  * - **Per-platform continue-on-error.** A developer building on
  *   `osx-aarch64` should not fail their build when the `linux-x86_64`
  *   asset is missing or the GitHub Release is still draft. We log
  *   warnings and continue, returning whichever platforms succeeded.
  *   The runtime check in `NativeLoader.loadFromResources` will still
  *   reject a missing-for-this-platform asset with a clear error -
  *   that's a different (and correct) failure mode than failing the
  *   whole sbt session.
  *
  * - **On-disk caching.** Once a platform binary lands in
  *   `resourceManaged/native/<platform>/`, we skip the HTTP fetch. sbt
  *   garbage-collects `resourceManaged` on `clean`, which is the right
  *   knob for "force a re-download".
  *
  * - **Local-build fallback.** When the developer has built a local
  *   `native/quackwire/build/libquackwire.{so,dylib}` (Task 1.5
  *   workflow), we copy it into the matching platform slot before
  *   attempting any HTTP fetch. This keeps the C++-contributor inner
  *   loop snappy and avoids requiring a network round-trip after every
  *   local `cmake --build`.
  */
object QuackwireArtifact {

  val quackwireRelease = settingKey[String]("github release tag for libquackwire")
  val fetchQuackwire   = taskKey[Seq[File]]("fetch matching libquackwire binaries into resources")

  // Co-pinned with the duckdb-quack short SHA `87cd65b912a8` per design
  // doc §9.7. CI's release-aggregation job publishes the tag in this
  // shape; bump both pins together (see §9.9 "co-pinned upstream
  // versions") when the SHA changes.
  private val DefaultReleaseTag = "quackwire-v0.1.0-duckdbquack-87cd65b912a8"

  private val Platforms = Seq(
    Platform("linux-x86_64",  "so"),
    Platform("linux-aarch64", "so"),
    Platform("osx-x86_64",    "dylib"),
    Platform("osx-aarch64",   "dylib")
  )

  // The GitHub repo is the same one the project is published from. Kept
  // as a constant rather than read from `git remote` so a developer
  // building from a fork still resolves the canonical release.
  private val ReleaseUrlBase =
    "https://github.com/starlake-ai/quack-on-demand/releases/download"

  // Connect / read timeouts on the HTTP fetch. Generous because GH
  // Release downloads occasionally bounce through redirects, but
  // capped to keep a bad network from hanging `sbt compile`.
  private val ConnectTimeoutMs = 10000
  private val ReadTimeoutMs    = 60000

  private final case class Platform(name: String, ext: String) {
    def libFileName: String = s"libquackwire.$ext"
    def assetName:   String = s"libquackwire-$name.$ext"
  }

  lazy val settings: Seq[Setting[_]] = Seq(
    quackwireRelease := DefaultReleaseTag,
    fetchQuackwire := {
      val log            = streams.value.log
      val tag            = quackwireRelease.value
      val baseDir        = baseDirectory.value
      val managedRoot    = (Compile / resourceManaged).value / "native"
      // The unmanaged source-tree slot a Task-1.5-era developer build
      // may still occupy. If a binary exists there we DO NOT also
      // write it under `resourceManaged/` - sbt's `copyResources`
      // refuses duplicate target mappings and would abort the build.
      val unmanagedRoot  = (Compile / resourceDirectory).value / "native"
      // The local cmake --build output the C++-contributor workflow
      // populates. May or may not exist.
      val localBuildDir  = baseDir / "native" / "quackwire" / "build"

      val produced = Platforms.flatMap { platform =>
        val unmanagedFile = unmanagedRoot / platform.name / platform.libFileName
        if (unmanagedFile.isFile && unmanagedFile.length() > 0L) {
          // The pre-Task-8 placeholder is still in place. Skip; the
          // unmanaged resources path already carries it into the jar.
          log.debug(
            s"libquackwire[${platform.name}] present at unmanaged $unmanagedFile; skipping generator"
          )
          Some(unmanagedFile)
        } else {
          val outDir  = managedRoot / platform.name
          val outFile = outDir / platform.libFileName
          IO.createDirectory(outDir)

          if (outFile.exists() && outFile.length() > 0L) {
            log.debug(
              s"libquackwire[${platform.name}] already cached at $outFile (${outFile.length()} bytes)"
            )
            Some(outFile)
          } else {
            // 1. Local build fallback: copy first, network second.
            val localCandidate = localBuildDir / platform.libFileName
            if (
              isLocalPlatform(platform) && localCandidate.isFile && localCandidate.length() > 0L
            ) {
              log.info(
                s"libquackwire[${platform.name}] copying local build $localCandidate -> $outFile"
              )
              IO.copyFile(localCandidate, outFile, preserveLastModified = true)
              Some(outFile)
            } else {
              // 2. HTTP fetch from the GitHub Release. Continue on error
              //    so other platforms still land.
              tryDownload(tag, platform, outFile, log) match {
                case Some(f) => Some(f)
                case None    => None
              }
            }
          }
        }
      }

      log.info(
        s"libquackwire artifacts ready: ${produced.size}/${Platforms.size}"
      )
      produced
    }
  )

  /** Best-effort HTTP fetch. Logs and returns `None` on failure rather
    * than aborting `fetchQuackwire` for the other platforms. Streams
    * straight to disk; deletes a partial file if the download fails
    * mid-flight so the next sbt run retries instead of seeing a
    * truncated cache entry.
    */
  private def tryDownload(
      tag: String,
      platform: Platform,
      out: File,
      log: util.Logger
  ): Option[File] = {
    val url = new URL(s"$ReleaseUrlBase/$tag/${platform.assetName}")
    log.info(s"libquackwire[${platform.name}] fetching $url")
    try {
      val conn = url.openConnection() match {
        case http: HttpURLConnection => http
        case other =>
          log.warn(s"libquackwire[${platform.name}] unexpected connection type: $other")
          return None
      }
      conn.setConnectTimeout(ConnectTimeoutMs)
      conn.setReadTimeout(ReadTimeoutMs)
      conn.setInstanceFollowRedirects(true)
      conn.setRequestProperty("User-Agent", "quack-on-demand-sbt-fetchQuackwire/1.0")

      val rc = conn.getResponseCode
      if (rc < 200 || rc >= 300) {
        // Common case before the first release is published: the tag
        // 404s. Stay quiet at info level; the parent task summary line
        // already reports "N/M ready", which is the operator signal.
        log.info(
          s"libquackwire[${platform.name}] skipped (HTTP $rc from $url)"
        )
        conn.disconnect()
        return None
      }

      val in = conn.getInputStream
      try
        IO.transfer(in, out)
      finally {
        in.close()
        conn.disconnect()
      }

      if (out.length() == 0L) {
        log.warn(s"libquackwire[${platform.name}] empty download; discarding $out")
        IO.delete(out)
        None
      } else {
        log.info(
          s"libquackwire[${platform.name}] downloaded ${out.length()} bytes -> $out"
        )
        Some(out)
      }
    } catch {
      case e: IOException =>
        // Partial write -> drop the truncated file so the next run retries.
        if (out.exists()) IO.delete(out)
        log.warn(
          s"libquackwire[${platform.name}] download failed (${e.getClass.getSimpleName}: ${e.getMessage}); continuing"
        )
        None
    }
  }

  /** True when the platform matches the JVM running sbt. Used to drive
    * the local-build fallback - copying a `native/quackwire/build/`
    * artifact only makes sense for the current OS+arch.
    */
  private def isLocalPlatform(platform: Platform): Boolean = {
    val osName  = sys.props.getOrElse("os.name", "").toLowerCase
    val osArch  = sys.props.getOrElse("os.arch", "").toLowerCase
    val osTag =
      if (osName.contains("mac"))   "osx"
      else if (osName.contains("linux")) "linux"
      else ""
    val archTag = osArch match {
      case "x86_64" | "amd64"  => "x86_64"
      case "aarch64" | "arm64" => "aarch64"
      case _                   => ""
    }
    osTag.nonEmpty && archTag.nonEmpty && platform.name == s"$osTag-$archTag"
  }
}