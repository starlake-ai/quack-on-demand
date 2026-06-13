import sbt._

object Dependencies {
  val tapirCore             = "com.softwaremill.sttp.tapir" %% "tapir-core" % Versions.tapir
  val tapirHttp4sServer     = "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir
  val tapirJsonCirce        = "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % Versions.tapir
  val tapirSwaggerUiBundle  = "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % Versions.tapir
  val tapirFiles            = "com.softwaremill.sttp.tapir" %% "tapir-files" % Versions.tapir
  val tapirOpenapiDocs      = "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % Versions.tapir

  val sttpApispecOpenapiCirceYaml = "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % Versions.sttpApispec

  val http4sEmberServer     = "org.http4s" %% "http4s-ember-server" % Versions.http4s
  val http4sEmberClient     = "org.http4s" %% "http4s-ember-client" % Versions.http4s
  val http4sBlazeClient     = "org.http4s" %% "http4s-blaze-client" % "0.23.16"
  val http4sDsl             = "org.http4s" %% "http4s-dsl" % Versions.http4s
  val http4sCirce           = "org.http4s" %% "http4s-circe" % Versions.http4s

  val circeCore             = "io.circe" %% "circe-core" % Versions.circe
  val circeGeneric          = "io.circe" %% "circe-generic" % Versions.circe
  val circeParser           = "io.circe" %% "circe-parser" % Versions.circe
  val circeYaml             = "io.circe" %% "circe-yaml-v12" % Versions.circeYaml

  val arrowFlight           = "org.apache.arrow" % "arrow-flight" % Versions.arrow
  val flightSql             = "org.apache.arrow" % "flight-sql" % Versions.arrow
  // arrow-memory-unsafe avoids arrow-memory-netty's reflection on Netty
  // internals (PooledByteBufAllocatorL.chunkSize, removed in Netty 4.1.100+).
  // The K8s mock pulls Vert.x → Netty 4.1.118; unsafe is the only path that
  // satisfies both classpaths.
  val arrowMemoryUnsafe     = "org.apache.arrow" % "arrow-memory-unsafe" % Versions.arrow
  // arrow-c-data exposes ArrowArrayStream, which DuckDB JDBC's
  // arrowExportStream() converts into.
  val arrowCData            = "org.apache.arrow" % "arrow-c-data" % Versions.arrow

  val grpcNetty             = "io.grpc" % "grpc-netty" % Versions.grpc
  val grpcStub              = "io.grpc" % "grpc-stub" % Versions.grpc

  val scalaLogging          = "com.typesafe.scala-logging" %% "scala-logging" % Versions.scalaLogging
  val logbackClassic        = "ch.qos.logback" % "logback-classic" % Versions.logbackClassic

  val pureconfigCore        = "com.github.pureconfig" %% "pureconfig-core" % Versions.pureconfig
  val pureconfigGenericScala3 = "com.github.pureconfig" %% "pureconfig-generic-scala3" % Versions.pureconfig

  val javaJwt               = "com.auth0" % "java-jwt" % Versions.javaJwt
  val nimbusJoseJwt         = "com.nimbusds" % "nimbus-jose-jwt" % Versions.nimbusJoseJwt

  val jsqlParser            = "com.manticore-projects.jsqlformatter" % "jsqlparser" % Versions.jsqlParser
  val jsqlTranspiler        = "ai.starlake.jsqltranspiler" % "jsqltranspiler" % Versions.jsqlTranspiler
  val catsCore              = "org.typelevel" %% "cats-core" % Versions.cats
  val kubernetesClient      = "io.fabric8" % "kubernetes-client" % Versions.fabric8
  val kubernetesServerMock  = "io.fabric8" % "kubernetes-server-mock" % Versions.fabric8 % Test
  // junit is 'provided' in kubernetes-server-mock; pull it explicitly for the test classpath
  // because KubernetesServer extends org.junit.rules.ExternalResource.
  val junit4                = "junit" % "junit" % "4.13.2" % Test

  val hikariCp              = "com.zaxxer" % "HikariCP" % Versions.hikariCp
  val jbcrypt               = "at.favre.lib" % "bcrypt" % Versions.jbcrypt
  val postgresql            = "org.postgresql" % "postgresql" % Versions.postgresql
  // Liquibase applies YAML changelogs under `db/changelog/` at startup —
  // one source of truth for the `qodstate_*` control-plane schema.
  val liquibaseCore         = "org.liquibase" % "liquibase-core" % Versions.liquibase

  val scalaTest             = "org.scalatest" %% "scalatest" % Versions.scalaTest % Test
  val wireMock              = "org.wiremock" % "wiremock" % Versions.wireMock % Test

  // fs2-blobstore (multi-cloud ACL storage)
  val blobstoreCore         = "com.github.fs2-blobstore" %% "core" % Versions.blobstore
  val blobstoreS3           = "com.github.fs2-blobstore" %% "s3" % Versions.blobstore
  val blobstoreGcs          = "com.github.fs2-blobstore" %% "gcs" % Versions.blobstore
  val blobstoreAzure        = "com.github.fs2-blobstore" %% "azure" % Versions.blobstore

  // DuckDB JDBC (for catalog resolver)
  val duckdbJdbc            = "org.duckdb" % "duckdb_jdbc" % Versions.duckdb

  // Micrometer metrics (prometheus, cloudwatch, azure, stackdriver registries)
  val micrometerCore        = "io.micrometer" % "micrometer-core"                   % Versions.micrometer
  val micrometerPrometheus  = "io.micrometer" % "micrometer-registry-prometheus"    % Versions.micrometer
  val micrometerCloudwatch  = "io.micrometer" % "micrometer-registry-cloudwatch2"   % Versions.micrometer
  val micrometerAzure       = "io.micrometer" % "micrometer-registry-azure-monitor" % Versions.micrometer
  val micrometerStackdriver = "io.micrometer" % "micrometer-registry-stackdriver"   % Versions.micrometer
}