package ai.starlake.quack.ondemand.security.checks

import ai.starlake.quack.ondemand.security.*
import cats.effect.IO

object NodeDataChecks:

  private val unsigned = SecurityCheck(
    "node.unsigned-extensions",
    Area.Data,
    "Nodes may load unsigned extensions",
    List("QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS"),
    "/operating/hardening#extension-settings",
    ctx =>
      IO.pure(
        if !ctx.bool("quack-on-demand.nodeExtensions.allowUnsigned") then
          Outcome(Status.Ok, "Unsigned extensions are refused.")
        else
          Outcome(
            Status.Critical,
            "QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS is true: a superuser's statement or init SQL can load native code nobody signed.",
            "Remove QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS and restart the nodes."
          )
      )
  )

  private val community = SecurityCheck(
    "node.community-extensions",
    Area.Data,
    "Nodes may load community or auto-installed extensions",
    List("QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS", "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS"),
    "/operating/hardening#extension-settings",
    ctx =>
      IO.pure {
        val on = List(
          "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS" -> "quack-on-demand.nodeExtensions.allowCommunity",
          "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS" -> "quack-on-demand.nodeExtensions.autoinstallKnown"
        ).collect { case (env, path) if ctx.bool(path) => env }
        if on.isEmpty then Outcome(Status.Ok, "Community extensions and autoinstall are off.")
        else
          Outcome(
            Status.Warn,
            s"${on.mkString(" and ")} on: nodes load extension code from outside the official repository or on first use.",
            "Remove the settings and INSTALL what nodes need in the database's or pool's initSql."
          )
      }
  )

  private val localFiles = SecurityCheck(
    "node.local-files",
    Area.Data,
    "Nodes may open local files though no database needs them",
    List("QOD_NODE_ALLOW_LOCAL_FILES", "QOD_TENANT_FILES_ROOT"),
    "/operating/tenants-databases#secure-in-production",
    ctx =>
      if !ctx.bool("quack-on-demand.nodeFiles.allowLocal") then
        IO.pure(Outcome(Status.Ok, "Nodes open no local file outside DuckDB's temp directory."))
      else if ctx.string("quack-on-demand.tenantFiles.root").nonEmpty then
        IO.pure(
          Outcome(
            Status.Ok,
            "Tenant folders are authorized under QOD_TENANT_FILES_ROOT, so nodes need local files."
          )
        )
      else
        IO.blocking((ctx.facts.localDatabases(), ctx.facts.memoryDatabases())).map {
          case (local, _) if local.nonEmpty =>
            Outcome(
              Status.Ok,
              if local.size == 1 then
                "1 database keeps its data on a local path, so nodes need local files."
              else
                s"${local.size} databases keep their data on local paths, so nodes need local files."
            )
          case (_, memory) if memory.nonEmpty =>
            Outcome(
              Status.Ok,
              s"No database keeps its data on a local path, but memory databases (${memory.mkString(", ")}) may read local files, as the views `qod serve` makes over a local file do."
            )
          case _ =>
            Outcome(
              Status.Warn,
              "No database keeps its data on a local path or is a memory database, yet nodes may open local files.",
              "Set QOD_NODE_ALLOW_LOCAL_FILES=false and restart the nodes."
            )
        }
  )

  private val plainHttpEndpoints = SecurityCheck(
    "data.s3-endpoints-plain-http",
    Area.Data,
    "Users' S3 keys may travel over plain HTTP",
    List("QOD_USER_S3_ALLOWED_ENDPOINTS"),
    "/operating/hardening#files-and-s3",
    ctx =>
      IO.pure {
        val plain = ctx
          .csv("quack-on-demand.userS3.allowedEndpoints")
          .filter(_.toLowerCase(java.util.Locale.ROOT).startsWith("http://"))
        if plain.isEmpty then Outcome(Status.Ok, "No plain-HTTP S3 endpoint is allowed.")
        else
          Outcome(
            Status.Warn,
            s"Plain-HTTP endpoints allowed for users' own S3 keys: ${plain.mkString(", ")}.",
            "List only TLS endpoints (host[:port]) in QOD_USER_S3_ALLOWED_ENDPOINTS."
          )
      }
  )

  private val unencrypted = SecurityCheck(
    "data.unencrypted",
    Area.Data,
    "Databases are not encrypted at rest",
    List("QOD_REQUIRE_ENCRYPTION"),
    "/operating/encryption#secure-in-production",
    ctx =>
      if ctx.bool("quack-on-demand.requireEncryption") then
        IO.pure(Outcome(Status.Ok, "QOD_REQUIRE_ENCRYPTION refuses unencrypted creates."))
      else
        IO.blocking(ctx.facts.unencryptedDatabases()).map { counts =>
          if counts.isEmpty then
            Outcome(Status.Ok, "Every DuckLake and duckdb-file database is encrypted.")
          else
            Outcome(
              Status.Warn,
              "Unencrypted databases: " + counts.map((t, n) => s"$n in $t").mkString(", ") + ".",
              "Set QOD_REQUIRE_ENCRYPTION=true for new databases; encryption is chosen at create time only."
            )
        }
  )

  private def listed(
      id: String,
      title: String,
      docs: String,
      okText: String,
      what: String,
      fix: String
  )(
      read: AuditFacts => List[String]
  ) =
    SecurityCheck(
      id,
      Area.Data,
      title,
      Nil,
      docs,
      ctx =>
        IO.blocking(read(ctx.facts)).map { names =>
          if names.isEmpty then Outcome(Status.Ok, okText)
          else Outcome(Status.Warn, s"$what: ${names.mkString(", ")}.", fix)
        }
    )

  val all: List[SecurityCheck] = List(
    unsigned,
    community,
    localFiles,
    plainHttpEndpoints,
    unencrypted,
    listed(
      "data.untrusted-initsql",
      "Init SQL written by tenant admins",
      "/operating/tenants-databases#init-sql-trust",
      "No database or pool holds untrusted initSql.",
      "Untrusted initSql (runs under the allowlist; statements outside it are skipped)",
      "Review each text; a superuser re-saving it unchanged marks it trusted."
    )(_.untrustedInitSql()),
    listed(
      "data.writable-federation-with-secrets",
      "Writable federated sources hold secrets",
      "/operating/federation#secure-in-production",
      "Every federated source holding secrets is read-only.",
      "Writable federated sources holding secrets",
      "Make them read-only (readOnly: true) unless tenants must write through them."
    )(_.writableSourcesWithSecrets()),
    listed(
      "data.branch-no-ttl",
      "Branches without a TTL",
      "/operating/branching#secure-in-production",
      "Every live branch has a TTL.",
      "Live branches with no TTL (each holds back snapshot expiry on its parent)",
      "Merge or discard them, and keep QOD_BRANCH_DEFAULT_TTL_HOURS above 0."
    )(_.liveBranchesWithoutTtl())
  )
