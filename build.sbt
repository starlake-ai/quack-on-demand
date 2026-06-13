import xerial.sbt.Sonatype.sonatypeCentralHost
import ReleaseTransformations._

// Version lives in `version.sbt` so sbt-release can rewrite it across the
// release / next-snapshot bumps without touching this file.
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "ai.starlake"

// ----- Sonatype Central Portal publishing (snapshot + release-ready) -----
// The published artifact is the assembly uber-jar - quack-on-demand is an
// app, not an embed-style library, so consumers run `java -jar` rather
// than pulling transitive deps via Maven resolution. The regular -classes
// jar is disabled for the same reason (would be misleading without deps).
//
// Sonatype OSSRH (s01.oss.sonatype.org / oss.sonatype.org) was sunset in
// 2025; all new publishing goes through the Central Portal at
// central.sonatype.com. sbt-sonatype 3.12+ knows this host via
// `sonatypeCentralHost`. Generate the SONATYPE_USERNAME / SONATYPE_PASSWORD
// secrets from https://central.sonatype.com/account (User Token), NOT the
// legacy OSSRH credentials.
ThisBuild / publishMavenStyle      := true
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost
ThisBuild / sonatypeProfileName    := "ai.starlake"
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else                  sonatypePublishToBundle.value
}
ThisBuild / credentials += Credentials(
  "Sonatype Central Portal",
  sonatypeCentralHost,
  sys.env.getOrElse("SONATYPE_USERNAME", ""),
  sys.env.getOrElse("SONATYPE_PASSWORD", "")
)
ThisBuild / homepage := Some(url("https://github.com/starlake-ai/quack-on-demand"))
ThisBuild / licenses := Seq(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/starlake-ai/quack-on-demand"),
    "scm:git:git@github.com:starlake-ai/quack-on-demand.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id    = "hayssams",
    name  = "Hayssam Saleh",
    email = "hayssam.saleh@starlake.ai",
    url   = url("https://github.com/hayssams")
  )
)

lazy val genOpenApi    = taskKey[Unit]("Generate website/static/openapi.yaml from the Tapir endpoints")
lazy val genConfigDocs = taskKey[Unit]("Generate website/docs/reference/configuration.md from ConfigRegistry")

// ----- libquackwire (Maven Central) -----------------------------------------
// JNI shim native binaries published as classifier-per-platform jars.
//
// Version format:  <duckdb-abi>-<duckdb-quack-short-sha>-<rev>[-SNAPSHOT]
// Example:         1.5.3-87cd65b912a8-1-SNAPSHOT
//
//   duckdb-abi              the DuckDB libduckdb release the C++ shim
//                           links against (1.5.3)
//   duckdb-quack-short-sha  the pinned `native/quackwire/thirdparty/
//                           duckdb-quack` commit
//   rev                     monotonic patch number; bumps each time we
//                           release for the same (abi, sha) pair. Lets
//                           us re-release after a C++ fix without
//                           bumping the duckdb-quack pin.
//   -SNAPSHOT (suffix)      present during dev -> publishes to Central
//                           snapshots (mutable, no GPG); stripped by
//                           `scripts/release.sh` before publishSigned +
//                           sonatypeBundleRelease, then bumped to
//                           rev+1-SNAPSHOT for the next dev cycle.
//
// Bumping the duckdb-quack pin: update the submodule SHA, edit the SHA
// segment here, and reset rev to 1.
val libquackwireVersion = "1.5.3-87cd65b912a8-4"

lazy val libquackwire = (project in file("libquackwire"))
  .settings(
    name := "libquackwire",
    version := libquackwireVersion,
    description :=
      "JNI shim that speaks DuckDB Quack's binary wire (application/vnd.duckdb) " +
      "directly from a JVM. Native binaries published as classifier jars per platform.",
    crossPaths := false,         // not a Scala-versioned artifact
    autoScalaLibrary := false,   // no scala-library dependency
    // The `ThisBuild / publishTo` above keys off the root project's
    // `isSnapshot.value` (read from `version.sbt`), so a non-SNAPSHOT
    // libquackwire release would still get routed to Central snapshots
    // and rejected with HTTP 400 because the version doesn't end in
    // -SNAPSHOT. Override per-project so libquackwire's own version
    // picks the endpoint: snapshots for *-SNAPSHOT, release-bundle
    // staging otherwise.
    publishTo := {
      val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
      if (version.value.endsWith("-SNAPSHOT"))
        Some("central-snapshots" at centralSnapshots)
      else
        sonatypePublishToBundle.value
    },
    // sbt-sonatype's default `sonatypeBundleDirectory` is
    // `target / "sonatype-staging" / version.value` at ThisBuild scope,
    // so libquackwire's signed artifacts land under the root project's
    // `0.1.0-SNAPSHOT` directory. Override to libquackwire's own version
    // so `sonatypeBundleRelease` (invoked in the libquackwire project
    // scope by `scripts/release.sh`) finds its own bundle and the
    // SNAPSHOT check doesn't fire against the root version.
    sonatypeBundleDirectory :=
      (LocalRootProject / target).value / "sonatype-staging" / version.value,
    // Stub main jar (Central requires one). Contains just the README.
    Compile / packageBin / mappings := Seq(
      (baseDirectory.value / "README.md") -> "META-INF/libquackwire/README.md"
    ),
    // No sources / docs - the source-of-truth is the parent repo's
    // `native/quackwire/` directory at the duckdb-quack SHA in the version.
    Compile / packageSrc / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false,
    // Disable the noop compile task by emptying its source sets.
    Compile / unmanagedSourceDirectories := Seq.empty,
    // Four classifier artifacts. Each task zips
    // libquackwire/binaries/<platform>/libquackwire.<ext> into a jar
    // containing `native/<platform>/libquackwire.<ext>` - matching the
    // runtime resource path `QuackNativeBridge.loaded` looks up.
  )
  .settings(LibquackwireArtifacts.classifierSettings*)

lazy val root = (project in file("."))
  .settings(UiBuild.settings)
  .settings(
    name := "quack-on-demand",
    // Resolve `ai.starlake:libquackwire:*-SNAPSHOT` from Sonatype Central
    // snapshots. Released libquackwire versions (no -SNAPSHOT suffix)
    // are picked up from Maven Central via the default resolver.
    resolvers += "central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/",
    libraryDependencySchemes += "io.circe" %% "circe-yaml-common" % VersionScheme.Always,
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-buffer"                       % Versions.netty,
      "io.netty" % "netty-common"                       % Versions.netty,
      "io.netty" % "netty-codec"                        % Versions.netty,
      "io.netty" % "netty-codec-http"                   % Versions.netty,
      "io.netty" % "netty-codec-http2"                  % Versions.netty,
      "io.netty" % "netty-handler"                      % Versions.netty,
      "io.netty" % "netty-transport"                    % Versions.netty,
      "io.netty" % "netty-transport-native-unix-common" % Versions.netty,
      "io.netty" % "netty-resolver"                     % Versions.netty,
      "io.netty" % "netty-resolver-dns"                 % Versions.netty
    ),
    libraryDependencies ++= Seq(
      Dependencies.tapirCore,
      Dependencies.tapirHttp4sServer,
      Dependencies.tapirJsonCirce,
      Dependencies.tapirSwaggerUiBundle,
      Dependencies.tapirFiles,
      Dependencies.tapirOpenapiDocs,
      Dependencies.sttpApispecOpenapiCirceYaml,
      Dependencies.http4sEmberServer,
      Dependencies.http4sEmberClient,
      Dependencies.http4sDsl,
      Dependencies.http4sCirce,
      Dependencies.circeCore,
      Dependencies.circeGeneric,
      Dependencies.circeParser,
      Dependencies.circeYaml,
      Dependencies.arrowFlight,
      Dependencies.flightSql,
      Dependencies.arrowMemoryUnsafe,
      Dependencies.arrowCData,
      Dependencies.grpcNetty,
      Dependencies.grpcStub,
      Dependencies.scalaLogging,
      Dependencies.logbackClassic,
      Dependencies.pureconfigCore,
      Dependencies.pureconfigGenericScala3,
      Dependencies.javaJwt,
      Dependencies.nimbusJoseJwt,
      Dependencies.jsqlParser,
      Dependencies.kubernetesClient,
      Dependencies.kubernetesServerMock,
      Dependencies.junit4,
      Dependencies.hikariCp,
      Dependencies.jbcrypt,
      Dependencies.postgresql,
      Dependencies.liquibaseCore,
      Dependencies.catsCore,
      Dependencies.blobstoreCore,
      Dependencies.blobstoreS3,
      Dependencies.blobstoreGcs,
      Dependencies.blobstoreAzure,
      Dependencies.duckdbJdbc,
      // libquackwire JNI shim - one classifier dep per supported platform.
      // sbt-assembly bundles each classifier jar's payload into the uber-jar,
      // so the resulting `quack-on-demand-assembly-*.jar` carries
      // `native/<platform>/libquackwire.<so|dylib>` for all four platforms.
      // `QuackNativeBridge` resolves the matching one at runtime via
      // `getResourceAsStream("/native/<host-platform>/libquackwire.<ext>")`.
      "ai.starlake" % "libquackwire" % libquackwireVersion classifier "linux-x86_64",
      "ai.starlake" % "libquackwire" % libquackwireVersion classifier "linux-aarch64",
      "ai.starlake" % "libquackwire" % libquackwireVersion classifier "osx-x86_64",
      "ai.starlake" % "libquackwire" % libquackwireVersion classifier "osx-aarch64",
      Dependencies.micrometerCore,
      Dependencies.micrometerPrometheus,
      Dependencies.micrometerCloudwatch,
      Dependencies.micrometerAzure,
      Dependencies.micrometerStackdriver,
      Dependencies.scalaTest,
      Dependencies.wireMock,
      Dependencies.http4sBlazeClient % Test
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) =>
        xs match {
          case "MANIFEST.MF" :: Nil => MergeStrategy.discard
          case "services" :: _      => MergeStrategy.concat
          // Vert.x ships its version file under META-INF/vertx/. The
          // fabric8-kubernetes-client vert.x HTTP backend (used by
          // KubernetesQuackBackend) loads it on first .build() and dies
          // with "Cannot find vertx-version.txt on classpath" if the
          // assembly merge strategy discards it.
          case "vertx" :: _         => MergeStrategy.first
          case _                    => MergeStrategy.discard
        }
      case "module-info.class"       => MergeStrategy.discard
      case "application.conf"        => MergeStrategy.concat
      case "reference.conf"          => MergeStrategy.concat
      case x if x.endsWith(".proto") => MergeStrategy.rename
      case _                         => MergeStrategy.first
    },
    assembly / assemblyOutputPath := baseDirectory.value / "distrib" / (assembly / assemblyJarName).value,

    // Publish the assembly uber-jar as THE artifact (no separate "thin" jar).
    // `Compile / packageBin := assembly.value` makes sbt-sonatype upload the
    // assembled jar under the standard quack-on-demand_3-<version>.jar name,
    // which is exactly what `mvn dependency:get` consumers need to run
    // `java -jar` without resolving any transitive deps.
    Compile / packageBin := assembly.value,

    // Arrow Flight's arrow-memory-unsafe allocator reflects into java.nio
    // internals (Buffer.address etc.), which Java 17+ blocks under JPMS.
    // sbt run / sbt test must fork so javaOptions take effect.
    Compile / run / fork := true,
    Test / fork          := true,
    Compile / run / javaOptions ++= Seq(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),

    // For `java -jar distrib/...assembly.jar`: the Add-Opens manifest attribute
    // (JEP 261) makes the JVM apply the same opens automatically - users don't
    // have to pass --add-opens on the command line.
    assembly / packageOptions += Package.ManifestAttributes(
      "Add-Opens" -> "java.base/java.nio java.base/sun.nio.ch"
    ),

    // Pin the assembly's Main-Class. Without this, sbt-assembly leaves it
    // unset whenever more than one main is discovered (the `docs.GenOpenApi`
    // / `docs.GenConfigDocs` helpers added for site generation each carry
    // their own `def main`), and `java -jar` then fails with
    // "no main manifest attribute".
    assembly / mainClass := Some("ai.starlake.quack.Main"),

    // sbt-release flow - mirrors starlake-core's `sbt 'release with-defaults'`
    // dance: bump version (drop -SNAPSHOT), commit, tag, sign+publish to
    // Central Portal, release the bundle, bump to next snapshot, commit,
    // push. Tests + clean are skipped because the assembly already does
    // a full compile and we want releases to be fast/predictable; flip them
    // on if you start cutting releases from dirty trees.
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      releaseStepCommandAndRemaining("publishSigned"),
      releaseStepCommand("sonatypeBundleRelease"),
      setNextVersion,
      commitNextVersion,
      pushChanges
    ),
    genOpenApi := Def.taskDyn {
      val v = version.value
      (Compile / runMain).toTask(
        s" ai.starlake.quack.docs.GenOpenApi website/static/openapi.yaml $v"
      )
    }.value,
    genConfigDocs := Def.taskDyn {
      (Compile / runMain)
        .toTask(" ai.starlake.quack.docs.GenConfigDocs website/docs/reference/configuration.md")
    }.value
  )