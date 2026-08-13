import xerial.sbt.Sonatype.sonatypeCentralHost

// Version lives in `version.sbt` so scripts/release-jar.sh can rewrite it
// across the release / next-snapshot bumps without touching this file.
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "ai.starlake"

// pureconfig's Mirror-based deriveReader macro recurses once per ManagerConfig
// field; the scala3 compiler default (-Xmax-inlines:32) is exceeded once
// ManagerConfig grows past ~28 fields (hit adding AutoscaleConfig). Bump with
// headroom rather than re-tuning on every future config block.
ThisBuild / scalacOptions += "-Xmax-inlines:64"

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

// ----- libquackwire (vendored native binaries) -------------------------------
// JNI shim native binaries.
//
// Version format:  <duckdb-abi>-<duckdb-quack-short-sha>-<rev>[-SNAPSHOT]
// Example:         1.5.5-7e80f7ffcc98-1-SNAPSHOT
//
//   duckdb-abi              the DuckDB libduckdb release the C++ shim
//                           links against (1.5.5)
//   duckdb-quack-short-sha  the pinned `native/quackwire/thirdparty/
//                           duckdb-quack` commit
//   rev                     monotonic patch number; bumps each time we
//                           release for the same (abi, sha) pair. Lets
//                           us re-release after a C++ fix without
//                           bumping the duckdb-quack pin.
// The binaries are vendored in-repo under libquackwire/binaries/ and
// refreshed by scripts/refresh-quackwire-binaries.sh; this val is no
// longer a Maven coordinate. run-jar.sh greps it for the libduckdb ABI
// check and the refresh script stamps it into libquackwire/binaries/VERSION.
//
// Bumping the duckdb-quack pin: update the submodule SHA, edit the SHA
// segment here, and reset rev to 1.
//
// ALSO revisit native/quackwire/src/win_duckdb_compat.cpp: it hard-codes a few
// DuckDB internal leaf symbols (SerializationCompatibility default version index,
// etc.) copied from the pinned DuckDB source. A DuckDB bump can change those
// values or introduce new unresolved symbols on the Windows link. See that
// file's header for how to re-derive them.
val libquackwireVersion = "1.5.5-7e80f7ffcc98-1"

lazy val root = (project in file("."))
  .settings(UiBuild.settings)
  .settings(
    name := "quack-on-demand",
    // Vendored libquackwire natives: copy libquackwire/binaries/<p>/<lib> into
    // resourceManaged as native/<p>/<lib> - the same classpath layout the
    // retired Maven classifier jars produced, so QuackNativeBridge's
    // getResourceAsStream("/native/<platform>/...") lookup is unchanged.
    // Mandatory platforms fail the build when absent so an incomplete
    // checkout cannot produce a silently-broken uber-jar; Windows rides in
    // whenever its dll is present (no env flag).
    Compile / resourceGenerators += Def.task {
      val srcRoot   = baseDirectory.value / "libquackwire" / "binaries"
      val outRoot   = (Compile / resourceManaged).value / "native"
      val mandatory = Seq(
        "linux-x86_64"  -> "libquackwire.so",
        "linux-aarch64" -> "libquackwire.so",
        "osx-x86_64"    -> "libquackwire.dylib",
        "osx-aarch64"   -> "libquackwire.dylib"
      )
      val optional = Seq("windows-x86_64" -> "quackwire.dll")
      val missing  = mandatory.filterNot { case (p, f) => (srcRoot / p / f).exists }
      if (missing.nonEmpty)
        sys.error(
          "missing vendored libquackwire binaries: " + missing.map(_._1).mkString(", ") +
            " under libquackwire/binaries/. Run scripts/refresh-quackwire-binaries.sh (or QOD_VERSION=BUILD ./scripts/run-jar.sh for the host platform)."
        )
      val present = mandatory ++ optional.filter { case (p, f) => (srcRoot / p / f).exists }
      present.map { case (p, f) =>
        val out = outRoot / p / f
        IO.copyFile(srcRoot / p / f, out)
        out
      }
    }.taskValue,
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
      Dependencies.julToSlf4j,
      Dependencies.pureconfigCore,
      Dependencies.pureconfigGenericScala3,
      Dependencies.javaJwt,
      Dependencies.nimbusJoseJwt,
      Dependencies.jsqlParser,
      Dependencies.jsqltranspiler,
      Dependencies.kubernetesClient,
      Dependencies.kubernetesServerMock,
      Dependencies.junit4,
      Dependencies.hikariCp,
      Dependencies.jbcrypt,
      Dependencies.postgresql,
      Dependencies.embeddedPostgres,
      Dependencies.liquibaseCore,
      Dependencies.catsCore,
      Dependencies.blobstoreCore,
      Dependencies.blobstoreS3,
      Dependencies.blobstoreGcs,
      Dependencies.blobstoreAzure,
      Dependencies.awsS3,
      Dependencies.duckdbJdbc,
      Dependencies.micrometerCore,
      Dependencies.micrometerPrometheus,
      Dependencies.micrometerCloudwatch,
      Dependencies.micrometerAzure,
      Dependencies.micrometerStackdriver,
      Dependencies.caffeine,
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