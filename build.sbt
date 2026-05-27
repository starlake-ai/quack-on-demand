ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "ai.starlake"

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
    // (JEP 261) makes the JVM apply the same opens automatically — users don't
    // have to pass --add-opens on the command line.
    assembly / packageOptions += Package.ManifestAttributes(
      "Add-Opens" -> "java.base/java.nio java.base/sun.nio.ch"
    )
  )