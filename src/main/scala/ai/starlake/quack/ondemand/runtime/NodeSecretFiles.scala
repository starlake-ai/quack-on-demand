package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.TenantDb
import com.typesafe.scalalogging.LazyLogging

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{
  AclEntry,
  AclEntryPermission,
  AclEntryType,
  AclFileAttributeView,
  FileTime,
  PosixFileAttributeView,
  PosixFilePermissions
}
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** How a node's sensitive values reach the spawn script without ever sitting in a process
  * environment or command line.
  *
  * A node user with SQL can read `getenv()` and `/proc/<pid>/{environ,cmdline}` of the duckdb
  * process and of its wrapper script (the initial environment block survives an `unset`), so every
  * launcher (local [[LocalQuackBackend]], fleet `qod fleet join`, Kubernetes
  * [[KubernetesQuackBackend]]) hands the script a DIRECTORY holding one file per key and passes
  * only its path ([[DirEnv]]). The local and fleet launchers create it private to the current user
  * and mark it ephemeral ([[EphemeralEnv]]): the script deletes it as soon as it has read the
  * values. On Kubernetes it is a read-only Secret volume.
  */
object NodeSecretFiles extends LazyLogging:

  /** Env var naming the secrets directory. */
  val DirEnv: String = "QOD_SECRETS_DIR"

  /** Env var set to `1` when the script must delete [[DirEnv]] after reading it. */
  val EphemeralEnv: String = "QOD_SECRETS_DIR_EPHEMERAL"

  /** File holding the bearer token the manager presents to the node. */
  val TokenKey: String = "QOD_NODE_TOKEN"

  /** File holding the node's key (64 lowercase hex digits), derived from the manager's node-key
    * secret and the node id ([[ai.starlake.quack.edge.authz.NodeKeys]]): every session handle the
    * node accepts is signed with it. qod-node reads it itself.
    */
  val AuthzKey: String = "QOD_NODE_AUTHZ_KEY"

  /** Env var naming the node's id, which qod-node checks every session handle against. Not a
    * secret.
    */
  val NodeIdEnv: String = "QOD_NODE_ID"

  /** Files holding the tenant-db's and the pool's initSql, secrets like the federation SQL (a
    * tenant's init SQL may carry credentials). The file's name says who wrote it: the spawn scripts
    * run a `...Trusted` file (a superuser's or the manager's) as written and hand any other to
    * qod-node in a marked section, under its tenant initSql allowlist. The trust rides the same
    * object as the SQL on every backend, so no node can pair one value with the other's trust (a
    * Kubernetes pod projects only the key its spec holds, from a Secret every spawn rewrites).
    */
  val TenantDbInitKey: String          = "tenantDbInitSql"
  val TenantDbInitTrustedKey: String   = "tenantDbInitSqlTrusted"
  val TenantPoolInitKey: String        = "tenantPoolInitSql"
  val TenantPoolInitTrustedKey: String = "tenantPoolInitSqlTrusted"

  /** The file of this spawn's init marker nonce (32 lowercase hex digits, fresh per start): the
    * spawn script writes it into both lines marking a tenant's section, and qod-node, which reads
    * it with the token, honours only marker lines carrying it. Nothing a tenant writes can know it.
    */
  val InitNonceKey: String = "QOD_INIT_NONCE"

  /** A fresh [[InitNonceKey]] value. */
  def newInitNonce(): String =
    val bytes = new Array[Byte](16)
    new java.security.SecureRandom().nextBytes(bytes)
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /** The tenant initSql files of `spec`, each under the key its trust names, the empty ones left
    * out.
    */
  def tenantInitFiles(spec: ai.starlake.quack.model.NodeSpec): Map[String, String] =
    Map(
      (if spec.tenantDbInitTrusted then TenantDbInitTrustedKey else TenantDbInitKey) ->
        spec.tenantDbInitSql,
      (if spec.tenantPoolInitTrusted then TenantPoolInitTrustedKey else TenantPoolInitKey) ->
        spec.tenantPoolInitSql
    ).filter(_._2.nonEmpty)

  /** Object-store credentials the spawn script turns into a global `CREATE SECRET` for a remote
    * `dataPath`. Read from the manager's (or fleet join process's) own environment, never forwarded
    * as env.
    */
  val CloudCredKeys: Seq[String] = Seq(
    "QOD_S3_ACCESS_KEY_ID",
    "QOD_S3_SECRET_ACCESS_KEY",
    "QOD_AZURE_CONNECTION_STRING",
    "QOD_GCS_KEY_ID",
    "QOD_GCS_SECRET"
  )

  /** Every key the spawn scripts read from [[DirEnv]], and only from there (a value still found in
    * the environment is ignored). Same list, same order, as `SECRET_KEYS` in
    * `scripts/spawn-quack-node.sh` / `.ps1` and `_SECRET_KEYS` in `qod_cli/fleet_join.py`.
    */
  val Keys: Seq[String] =
    Seq(
      "pgPassword",
      TenantDb.EncryptionKeyName,
      "extraSetupSql",
      "objectStoreSql",
      TokenKey,
      AuthzKey,
      TenantDbInitKey,
      TenantDbInitTrustedKey,
      TenantPoolInitKey,
      TenantPoolInitTrustedKey,
      InitNonceKey
    ) ++ CloudCredKeys

  require(
    TenantDb.NodeSecretEnvKeys.subsetOf(Keys.toSet),
    "every TenantDb.NodeSecretEnvKeys member must be delivered by file"
  )

  /** The canonical spelling of `name` when it names a secret key, case-insensitively (a metastore
    * key spelled `PgPassword` is redacted from every API response, so it is a secret too).
    */
  def canonical(name: String): Option[String] = Keys.find(_.equalsIgnoreCase(name))

  def isKey(name: String): Boolean = canonical(name).isDefined

  /** Cloud SDK conventions DuckDB's own `credential_chain` providers read by design: left alone. */
  private val SdkPrefixes: Seq[String] = Seq("AWS_", "AZURE_", "GOOGLE_")

  private val SecretNamePattern =
    "(?i).*(PASSWORD|PASSWD|PASSFILE|SECRET|TOKEN|PASSPHRASE|CREDENTIAL|PRIVATE|API_?KEY|ACCESS_KEY|CONNECTION_STRING|JWT).*".r

  /** True for an inherited manager environment variable that must not reach a node process: the
    * manager's own credentials (`QOD_SESSION_JWT_SECRET`, `QOD_ADMIN_PASSWORD`, control-plane and
    * SMTP passwords, a module's payment key...) are readable by any node user through `getenv()`
    * otherwise. Cloud SDK variables (`AWS_*`, `AZURE_*`, `GOOGLE_*`) are kept: an operator may rely
    * on DuckDB's credential chain reading them.
    */
  def isInheritedSecret(name: String): Boolean =
    !SdkPrefixes.exists(p => name.toUpperCase(java.util.Locale.ROOT).startsWith(p)) &&
      SecretNamePattern.matches(name)

  /** The values to deliver by file, keyed by canonical name. The metastore (tenant-admin input)
    * supplies only its own secrets ([[NodeMetastore.secrets]]: `pgPassword`, `encryptionKey`), the
    * exact canonical spelling winning among spellings of one key; the cloud credentials, the SQL
    * (`tenantInit`: [[tenantInitFiles]]), the token, the node key and a fresh [[InitNonceKey]] all
    * come from the manager.
    */
  def collect(
      metastore: Map[String, String],
      extraSetupSql: String,
      objectStoreSql: String,
      tenantInit: Map[String, String] = Map.empty,
      token: String,
      authzKey: String,
      inheritedEnv: Map[String, String]
  ): Map[String, String] =
    val inherited = CloudCredKeys.flatMap(k => inheritedEnv.get(k).filter(_.nonEmpty).map(k -> _))
    val fromMetastore = NodeMetastore
      .secrets(metastore)
      .toSeq
      .flatMap { case (k, v) => canonical(k).map(c => (c, k == c, v)) }
      .groupBy(_._1)
      .map { case (c, entries) => c -> entries.sortBy(e => !e._2).head._3 }
    val sql = (Seq("extraSetupSql" -> extraSetupSql, "objectStoreSql" -> objectStoreSql) ++
      tenantInit.toSeq.filter { case (k, _) =>
        Set(TenantDbInitKey, TenantDbInitTrustedKey, TenantPoolInitKey, TenantPoolInitTrustedKey)
          .contains(k)
      }).filter(_._2.nonEmpty)
    (inherited.toMap ++ fromMetastore ++ sql + (TokenKey -> token) + (AuthzKey -> authzKey) +
      (InitNonceKey                                      -> newInitNonce())).filter(_._2.nonEmpty)

  /** Prefix of every secrets directory a launcher creates under the JVM temp dir. */
  val DirPrefix: String = "qod-node-secrets-"

  /** Write `values` to a fresh directory only the current user can read (0700 / files 0600 on
    * POSIX, an owner-only ACL on Windows). The directory is removed again on any failure.
    */
  def writePrivateDir(values: Map[String, String], parent: Option[Path] = None): Path =
    val dir = parent match
      case Some(p) => Files.createTempDirectory(p, DirPrefix)
      case None    => Files.createTempDirectory(DirPrefix)
    try
      restrictToOwner(dir, directory = true)
      values.foreach { case (k, v) =>
        require(canonical(k).contains(k), s"not a node secret key: $k")
        val f = dir.resolve(k)
        Files.createFile(f)
        restrictToOwner(f, directory = false)
        Files.write(f, v.getBytes(StandardCharsets.UTF_8))
      }
      dir
    catch
      case e: Throwable =>
        deleteQuietly(dir)
        throw e

  private def restrictToOwner(p: Path, directory: Boolean): Unit =
    Option(Files.getFileAttributeView(p, classOf[PosixFileAttributeView])) match
      case Some(posix) =>
        posix.setPermissions(
          PosixFilePermissions.fromString(if directory then "rwx------" else "rw-------")
        )
      case None =>
        // Neither view: fail closed rather than write secrets with the default permissions.
        val acl = Option(Files.getFileAttributeView(p, classOf[AclFileAttributeView])).getOrElse(
          throw new java.io.IOException(s"cannot restrict $p to its owner: no POSIX or ACL view")
        )
        val entry = AclEntry
          .newBuilder()
          .setType(AclEntryType.ALLOW)
          .setPrincipal(acl.getOwner)
          .setPermissions(AclEntryPermission.values().toSet.asJava)
          .build()
        acl.setAcl(java.util.List.of(entry))

  /** Recursively delete `dir`, ignoring every failure (the script may already have removed it). */
  def deleteQuietly(dir: Path): Unit =
    try
      if Files.exists(dir) then
        Files
          .walk(dir)
          .iterator()
          .asScala
          .toList
          .reverse
          .foreach(p => Files.deleteIfExists(p))
    catch case _: Throwable => ()

  /** Delete secrets directories older than `olderThanSec` left in `root` by a launcher that died
    * between writing one and its script reading it (both killed at once). A live spawn reads its
    * directory within milliseconds, so the age bound never races one.
    */
  def sweepStale(
      root: Path = Paths.get(System.getProperty("java.io.tmpdir")),
      olderThanSec: Long = 600L
  ): Unit =
    try
      val cutoff = FileTime.from(Instant.now().minusSeconds(olderThanSec))
      val stream = Files.list(root)
      try
        stream.iterator().asScala.toList.foreach { p =>
          if p.getFileName.toString.startsWith(DirPrefix) && Files.isDirectory(p)
            && Files.getLastModifiedTime(p).compareTo(cutoff) < 0
          then
            logger.warn(s"removing stale node secrets directory $p")
            deleteQuietly(p)
        }
      finally stream.close()
    catch case e: Throwable => logger.debug(s"stale node secrets sweep skipped: ${e.getMessage}")
