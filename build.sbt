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

lazy val root = (project in file("."))
  .settings(UiBuild.settings)
  .settings(
    name := "quack-on-demand",
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
      Dependencies.catsCore,
      Dependencies.blobstoreCore,
      Dependencies.blobstoreS3,
      Dependencies.blobstoreGcs,
      Dependencies.blobstoreAzure,
      Dependencies.duckdbJdbc,
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
    )
  )