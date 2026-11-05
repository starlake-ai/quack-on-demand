package ai.starlake.quack.model

/** A tenant's database. Each row maps 1:1 to a Postgres database (named after `name`, composed
  * `${tenant}_${suffix}` on the shared server) and owns its default catalog (DuckLake | local
  * .duckdb file | in-memory) plus any federated catalogs the admin has registered against it.
  */
final case class TenantDb(
    id: String,
    tenantId: String,
    name: String,
    kind: TenantDbKind,
    metastore: Map[String, String],
    dataPath: String,
    objectStore: Map[String, String] = Map.empty,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    disabled: Boolean = false,
    // Operator-authored per-database SQL prepended to every node's boot SQL for
    // pools of this database, BEFORE the pool's own initSql and the federation
    // blob. Engine defaults live here: SET temp_directory, SET memory_limit,
    // INSTALL/LOAD. Edits take effect on the next node spawn.
    initSql: String = "",
    // Whether `initSql` was written by a superuser or by the manager itself (nodes run it as
    // written) or by a tenant admin (nodes run it under qod-node's tenant initSql allowlist). Set
    // by every write from the writer (`InitSqlTrust.afterWrite`); Liquibase 0053.
    initSqlTrusted: Boolean = false,
    // Parent tenant-db id when this row is a branch catalog (Epic 1); None for every ordinary
    // tenant-db. Branch rows attach on their nodes under the PARENT's catalog alias (metastore
    // key `catalogAlias`), are excluded from maintenance scheduling, and are torn down through
    // the branch lifecycle rather than database/delete.
    branchOf: Option[String] = None,
    // Encrypt this database's data at rest. kind=ducklake passes ENCRYPTED on every ATTACH and
    // DuckLake mints a key per Parquet file into its own catalog; kind=duckdb-file passes
    // ENCRYPTION_KEY from `metastore(EncryptionKeyName)`. Create-time only: neither engine can
    // encrypt an existing database in place, so there is no update path and no field to refuse
    // on one.
    encrypted: Boolean = false
)

object TenantDb {

  /** Metastore key holding the per-database DuckDB file encryption key. Only ever set for
    * `kind=duckdb-file`: DuckLake keeps its own per-file keys in `ducklake_data_file`.
    */
  val EncryptionKeyName: String = "encryptionKey"

  /** Keys that must never round-trip in an API response, and that
    * [[ai.starlake.quack.ondemand.PoolSupervisor.updateTenantDb]] carries over from the stored map
    * when an update's incoming map omits them (no client can round-trip a value it was never
    * shown): the metastore `pgPassword` plus the object-store secret keys authored by
    * [[ai.starlake.quack.ondemand.runtime.ObjectStoreSecret]] (`s3_secret_access_key`,
    * `azure_account_key`, `gcs_hmac_secret`). Single source of truth shared by
    * `HandlerResolvers.redactPassword` (response redaction) and `PoolSupervisor.mergeSecretKeys`
    * (update merge) so the two sites cannot drift out of sync.
    */
  val SecretKeys: Set[String] =
    Set(
      "pgPassword",
      "s3_secret_access_key",
      "azure_account_key",
      "gcs_hmac_secret",
      EncryptionKeyName
    )

  /** Whether a metastore or `objectStore` key is one of [[SecretKeys]], in any spelling. */
  def isSecretKey(key: String): Boolean = SecretKeys.exists(_.equalsIgnoreCase(key))

  /** The metastore's own secrets, which every launcher delivers as files and never as an
    * environment variable (`NodeMetastore.secrets`: the spawn script's secrets directory, the
    * Kubernetes per-pool node-env Secret, a fleet assignment). Deliberately distinct from
    * [[SecretKeys]], which answers a different question (what must never round-trip through an API
    * response) and spans the `objectStore` map as well.
    */
  val NodeSecretEnvKeys: Set[String] = Set("pgPassword", EncryptionKeyName)

  /** Optional metastore key naming the DuckDB catalog alias the node ATTACHes the database under.
    * Absent (every ordinary tenant-db), the alias is `dbName`. Branch catalogs set it to their
    * parent's alias so grants, policies and user SQL written against the parent apply unchanged.
    */
  val CatalogAliasKey: String = "catalogAlias"

  /** Metastore key the manager stamps from the row's own `encrypted` flag for the spawn scripts
    * (`PoolSupervisor`'s effective metastore). Never a caller's key: [[KnownMetastoreKeys]] leaves
    * it out.
    */
  val EncryptedKey: String = "encrypted"

  /** Metastore key the manager stamps on a DuckLake row of QoD's own server (`PoolSupervisor`'s
    * effective metastore): only then may the node's launcher run `CREATE DATABASE` for a catalog
    * database that is missing. A catalog on any other server is its owner's to manage. Never a
    * caller's key: [[KnownMetastoreKeys]] leaves it out.
    */
  val CreateDatabaseKey: String = "createDatabase"

  /** The non-secret metastore keys a node's launcher reads from its environment:
    * `scripts/spawn-quack-node.sh` and `.ps1` read exactly these (the node image's entrypoint and
    * `qod fleet join` run the bash script). Every launcher overlay passes these and no other
    * metastore key: `LocalQuackBackend`'s spawn environment, `KubernetesQuackBackend`'s pod env,
    * `FleetQuackBackend`'s assignment env and `qod fleet join` (`_NODE_ENV_KEYS`, pinned equal by
    * `NodeSecretFilesSpec`). Any other name would become an environment variable of a bash script
    * and of qod-node: `BASH_ENV` (bash expands it before running the script), `PATH`, `LD_PRELOAD`,
    * `QOD_NODE_BIN`, or a variable the manager sets itself (`kind`, `QOD_NODE_ID`, the decide
    * limits `QOD_AUTHZ_DECIDE_TIMEOUT_MS` / `QOD_AUTHZ_CACHE_ENTRIES`). The metastore's secrets
    * ([[NodeSecretEnvKeys]]) reach the launcher as files, never env. A key the scripts start
    * reading from the metastore must be added here, or every launcher drops it.
    */
  val NodeEnvKeys: Set[String] =
    Set(
      "pgHost",
      "pgPort",
      "pgUser",
      "dbName",
      "catalogAlias",
      "schemaName",
      "dataPath",
      "encrypted",
      "createDatabase"
    )

  /** Every key a caller may store in a tenant-db's metastore, compared by exact name: the
    * launcher's [[NodeEnvKeys]] minus [[EncryptedKey]] and [[CreateDatabaseKey]] (stamped by the
    * manager), plus the secrets [[NodeSecretEnvKeys]]. They are also every metastore key
    * manager-side code reads (the DuckLake initializer, the catalog reader, the branch cloner and
    * merge fence). `database/create`, `database/update` and a manifest import refuse any other key
    * with `400 invalid_metastore_key` before anything is written, except that `database/update`
    * also takes a secret-named key ([[SecretKeys]]) sent empty, which removes it
    * ([[metastoreUpdateKeyError]]).
    */
  val KnownMetastoreKeys: Set[String] =
    (NodeEnvKeys - EncryptedKey - CreateDatabaseKey) ++ NodeSecretEnvKeys

  /** The clause naming [[KnownMetastoreKeys]], sorted, in every line that refuses or drops another
    * key: the API's refusal, a node's dropped key, a manifest export's left-out key.
    */
  val MetastoreHoldsOnly: String =
    s"a metastore holds only ${KnownMetastoreKeys.toList.sorted.mkString(", ")}"

  /** The keys of `metastore` outside [[KnownMetastoreKeys]], sorted. */
  def unknownMetastoreKeys(metastore: Map[String, String]): List[String] =
    metastore.keys.filterNot(KnownMetastoreKeys.contains).toList.sorted

  /** The refusal of a metastore holding a key outside [[KnownMetastoreKeys]], naming every such key
    * and never a value; None when every key is known.
    */
  def metastoreKeyError(metastore: Map[String, String]): Option[String] =
    unknownMetastoreKeys(metastore) match
      case Nil  => None
      case keys =>
        val (noun, verb) = if keys.size == 1 then ("key", "is") else ("keys", "are")
        val hint         =
          if keys.contains(EncryptedKey) then
            s" (`$EncryptedKey` is the database's own flag, set at create)"
          else ""
        Some(
          s"metastore $noun ${keys.map(k => s"'$k'").mkString(", ")} $verb not accepted: " +
            s"$MetastoreHoldsOnly$hint"
        )

  /** [[metastoreKeyError]] for `database/update`, which also takes a secret-named key outside
    * [[KnownMetastoreKeys]] (a [[SecretKeys]] member: `s3_secret_access_key`, `azure_account_key`,
    * `gcs_hmac_secret`) sent with an empty value. No response shows such a key and an update that
    * leaves it out keeps it (`PoolSupervisor.mergeSecretKeys`), so sending it empty, which removes
    * it in any spelling, is the one way to remove it from a row stored before the API refused it.
    * Sent with a value, it is refused like any other key.
    */
  def metastoreUpdateKeyError(metastore: Map[String, String]): Option[String] =
    metastoreKeyError(metastore.filterNot { case (k, v) => SecretKeys.contains(k) && v.isEmpty })

  /** A metastore key fit for a log line: every control character escaped, so a stored key cannot
    * forge a line.
    */
  def printableKey(key: String): String =
    key.flatMap(c => if c.isControl then f"\\u${c.toInt}%04x" else c.toString)

  /** How `database/update` removes `key`, a stored key outside [[KnownMetastoreKeys]] that no node
    * uses, for the lines that name one (a node's dropped key, a manifest export's left-out key).
    * Not for a spelling of [[NodeSecretEnvKeys]] (`PgPassword`): the nodes use that as their
    * secret, so it is stored under its own name, never removed. An update replaces the whole
    * metastore, so the advice always sends the full map: one sending only the key would revert
    * every other key (a branch row would lose its `catalogAlias`). A secret-named key
    * ([[SecretKeys]], in any spelling) is hidden from responses and kept by an update that leaves
    * it out, so it goes by sending its [[SecretKeys]] name empty, which removes every spelling
    * ([[metastoreUpdateKeyError]] accepts that).
    */
  def metastoreKeyRemoval(key: String): String =
    SecretKeys.find(_.equalsIgnoreCase(key)) match
      case Some(secret) =>
        "Remove it by sending the database's full metastore through database/update with " +
          s"'$secret' set to an empty value: an update replaces the metastore, and one that " +
          "leaves a secret key out keeps it."
      case None =>
        "Remove it by sending the database's full metastore without it through database/update."

  /** The login QoD fills in from its own defaults, and only on its own server
    * ([[inheritedDefaults]]).
    */
  val LoginKeys: Set[String] = Set("pgUser", "pgPassword")

  /** Whitespace ends a value in a libpq connection string, where the launchers write `host`,
    * `port`, `dbname` and `user` unquoted: no connection string reads such a value as one value.
    */
  private def breaksConnString(value: String): Boolean =
    value.exists(c => c <= ' ' || Character.isWhitespace(c) || Character.isSpaceChar(c))

  /** The metastore values a node's bootstrap writes around its connection: the connection string's
    * four, and the schema it then creates and uses. [[validateSafety]] refuses whitespace in each
    * on every write; [[onDefaultServer]] reads a stored one holding any as another server.
    */
  private val ConnStringKeys: List[String] =
    List("pgHost", "pgPort", "pgUser", "dbName", "schemaName")

  /** ASCII only, and no resolving: `127.0.0.1` is not `localhost`, so such a row names no host, or
    * brings its own login.
    */
  def normalizedHost(host: String): String =
    val trimmed = host.trim
    val bare    =
      if trimmed.startsWith("[") && trimmed.endsWith("]") then
        trimmed.substring(1, trimmed.length - 1)
      else trimmed
    bare.stripSuffix(".").map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

  def normalizedPort(port: String): String =
    val trimmed = port.trim
    if trimmed.nonEmpty && trimmed.forall(c => c >= '0' && c <= '9') then
      val digits = trimmed.dropWhile(_ == '0')
      if digits.isEmpty then "0" else digits
    else trimmed

  /** The server a metastore connects to: its own host and port, else the defaults', else what the
    * spawn scripts fall back on, normalized.
    */
  private def serverOf(
      metastore: Map[String, String],
      defaults: Map[String, String]
  ): (String, String) =
    def resolved(key: String, fallback: String) =
      metastore.get(key).orElse(defaults.get(key)).getOrElse(fallback)
    (normalizedHost(resolved("pgHost", "localhost")), normalizedPort(resolved("pgPort", "5432")))

  /** Whether `metastore` uses QoD's own Postgres server: the host and the port of `defaults`, once
    * normalized. A row holding a [[ConnStringKeys]] value no connection string reads as one value
    * does not, whatever its host says.
    */
  def onDefaultServer(metastore: Map[String, String], defaults: Map[String, String]): Boolean =
    serverOf(metastore, defaults) == serverOf(Map.empty, defaults) &&
      !ConnStringKeys.exists(k => metastore.get(k).exists(breaksConnString))

  /** What of the manager's defaults a row inherits: all of them on QoD's own server, every one but
    * [[LoginKeys]] on another, whoever wrote the row.
    */
  def inheritedDefaults(
      metastore: Map[String, String],
      defaults: Map[String, String]
  ): Map[String, String] =
    if onDefaultServer(metastore, defaults) then defaults else defaults -- LoginKeys

  /** Whether `key` names the host, the port or the user of a Postgres server, in any spelling. */
  def isServerAddressKey(key: String): Boolean =
    List("pgHost", "pgPort", "pgUser").exists(_.equalsIgnoreCase(key))

  /** What a caller who is not a superuser is shown of `shown`, a metastore that `stored` resolved
    * to: nothing the manager's defaults filled in and, on QoD's own server, neither its host, its
    * port nor its user, whoever stored them. A row on another server shows its own.
    */
  def metastoreShownToTenant(
      shown: Map[String, String],
      stored: Map[String, String],
      defaults: Map[String, String]
  ): Map[String, String] =
    val ownServer = onDefaultServer(stored, defaults)
    shown.filterNot { (k, v) =>
      (ownServer && isServerAddressKey(k)) || (!stored.contains(k) && defaults.get(k).contains(v))
    }

  /** What a caller who is not a superuser reads in place of an error naming QoD's Postgres server.
    */
  val ServerTextWithheld: String =
    "this error names QoD's Postgres server, which only a superuser is shown: see the manager log"

  /** `text`, or [[ServerTextWithheld]] when it names the host, the port or the user of QoD's
    * Postgres server as a connection's error does (`host=`, `host name "`, `server at "`,
    * `connection to `, `user "`, `role "`, `port `, `:<port>`). The whole text goes, as a client
    * library's wording around them says more than the values. A bare value is left: a refusal can
    * quote a name the caller sent, and withholding that would answer whether it is QoD's.
    */
  def textShownToTenant(text: String, defaults: Map[String, String]): String =
    def value(key: String) = defaults.get(key).map(_.trim).filter(_.nonEmpty)
    def quoted(v: String)  = java.util.regex.Pattern.quote(v)
    val named              = List(
      value("pgHost").map(h =>
        "(?i)(host[a-z ]*[=:\" ]+|server at \"?|connection to \"?|//|@)" +
          s"${quoted(h)}(?![A-Za-z0-9_-])"
      ),
      value("pgPort").map(p => s"(?i)(port[= ]+|:)${quoted(p)}(?![0-9])"),
      value("pgUser").map(u => s"(?i)((user|role)[= ]+\"|user=|//)${quoted(u)}(?![A-Za-z0-9_-])")
    ).flatten
    if named.exists(_.r.findFirstIn(text).isDefined) then ServerTextWithheld else text

  /** The one place a row's blanks are filled from the manager's defaults. */
  def withDefaults(
      metastore: Map[String, String],
      defaults: Map[String, String]
  ): Map[String, String] =
    inheritedDefaults(metastore, defaults) ++ metastore

  /** The refusal of a metastore on another server that does not bring both its own user and its own
    * password; None on QoD's own server.
    */
  def ownLoginError(
      metastore: Map[String, String],
      defaults: Map[String, String]
  ): Option[String] =
    if onDefaultServer(metastore, defaults) then None
    else
      // The password in any spelling, as the launchers read it.
      val holdsLogin = metastore.get("pgUser").exists(_.trim.nonEmpty) &&
        metastore.exists((k, v) => k.equalsIgnoreCase("pgPassword") && v.trim.nonEmpty)
      Option.when(!holdsLogin)(
        "this database names another Postgres server than QoD's own: provide a pgUser and a " +
          "pgPassword for that server, or leave pgHost and pgPort out to use QoD's own. QoD's " +
          "login is used only on its own server."
      )

  /** `database/update`: the refusal of a metastore that moves a row to another server with its
    * stored password carried over (`PoolSupervisor.mergeSecretKeys` keeps a secret an update leaves
    * out). The password is sent again for the new server, or sent empty to remove it. `stored` is
    * the row's metastore, `merged` the row's after the update, `sent` the update's own map.
    */
  def movedPasswordError(
      stored: Map[String, String],
      merged: Map[String, String],
      sent: Map[String, String],
      defaults: Map[String, String]
  ): Option[String] =
    def holdsPassword(m: Map[String, String]) =
      m.exists((k, v) => k.equalsIgnoreCase("pgPassword") && v.trim.nonEmpty)
    val moved = serverOf(stored, defaults) != serverOf(merged, defaults)
    Option.when(moved && holdsPassword(merged) && !sent.get("pgPassword").exists(_.trim.nonEmpty))(
      "this update changes the database's Postgres server, and its stored password does not follow " +
        "it: send pgPassword again for the new server."
    )

  /** The `objectStore` keys that make a remote data path sign with the database's own credentials,
    * by scheme, the ones `ObjectStoreSecret` reads; None for any other path. The schemes are the
    * spawn scripts', spelled as they match them.
    */
  private def ownKeyNames(dataPath: String): Option[List[String]] =
    if List("s3://", "s3a://", "r2://").exists(dataPath.startsWith) then
      Some(List("s3_access_key_id", "s3_secret_access_key"))
    else if dataPath.startsWith("gs://") then Some(List("gcs_hmac_key_id", "gcs_hmac_secret"))
    else if List("az://", "azure://", "abfss://").exists(dataPath.startsWith) then
      Some(List("azure_account", "azure_account_key"))
    else None

  /** Whether a node signs `dataPath` with keys of the database's own: a remote path, and every key
    * its scheme needs, explicit and not blank. A path is signed only with the keys the row holds,
    * or a superuser's. It reads presence, so a key that does not work counts.
    */
  def holdsOwnKeys(dataPath: String, objectStore: Map[String, String]): Boolean =
    ownKeyNames(dataPath).exists(_.forall(k => objectStore.get(k).exists(_.trim.nonEmpty)))

  /** Whether a remote path names a folder under its bucket or container, not their root in any
    * spelling, and each of its segments reads as itself: no `.` or `..`, no glob, no escape.
    */
  private def namesRemoteFolder(dataPath: String): Boolean =
    val segments =
      dataPath.substring(dataPath.indexOf("://") + 3).split('/').toList.filter(_.nonEmpty)
    segments.sizeIs > 1 &&
    segments.forall(s => s != "." && s != ".." && !s.exists("*?[]%".contains(_)))

  /** The metastore keys that name a Postgres server, a login on it and a database there. */
  val ServerKeys: List[String] = List("pgHost", "pgPort", "pgUser", "pgPassword", "dbName")

  /** What a metastore holds of [[ServerKeys]], as [[superuserChoiceError]] compares it. A password
    * is held or not, in any spelling, as the launchers read it, and never compared by value. A
    * `dbName` equal to the row's own name reads as none, as `effectiveMetastoreFor` resolves it,
    * and on a `duckdb-file` row it names a catalog in the file, no Postgres database.
    */
  private def heldServerKeys(
      kind: TenantDbKind,
      rowName: Option[String],
      metastore: Map[String, String]
  ): Map[String, String] =
    val named    = List("pgHost", "pgPort", "pgUser").flatMap(k => metastore.get(k).map(k -> _))
    val password =
      Option.when(metastore.keys.exists(_.equalsIgnoreCase("pgPassword")))("pgPassword" -> "")
    val database = Option
      .when(kind != TenantDbKind.DuckDbFile)(metastore.get("dbName"))
      .flatten
      .filterNot(rowName.contains)
      .map("dbName" -> _)
    (named ++ password ++ database).toMap

  /** Which of [[ServerKeys]] a write adds, changes or removes: the row it would leave (`metastore`)
    * compared with the stored one, since an update's map can drop a key, and a password sent
    * (`sent`) counting as a change whatever it says.
    */
  def changedServerKeys(
      stored: Option[TenantDb],
      kind: TenantDbKind,
      metastore: Map[String, String],
      sent: Map[String, String]
  ): List[String] =
    val before =
      stored.fold(Map.empty[String, String])(td =>
        heldServerKeys(td.kind, Some(td.name), td.metastore)
      )
    val after        = heldServerKeys(kind, stored.map(_.name), metastore)
    val passwordSent = sent.exists((k, v) => k.equalsIgnoreCase("pgPassword") && v.nonEmpty)
    ServerKeys.filter(k => before.get(k) != after.get(k) || (k == "pgPassword" && passwordSent))

  /** The refusal of an update naming a server key that a row on QoD's own server does not show its
    * writer, whatever the value says: such a key left out stays as stored.
    */
  def unshownServerKeyError(sent: Map[String, String]): Option[String] =
    Option.when(sent.keys.exists(isServerAddressKey))(
      "a superuser sets a database's Postgres server: leave pgHost, pgPort and pgUser out of this " +
        "update, and they stay as they are"
    )

  /** Where a row connects, which only a superuser names. A database's Postgres server is trusted as
    * far as its nodes are: whoever controls it reaches what those nodes reach. A superuser who
    * names one accepts that.
    */
  private def serverChoiceError(
      stored: Option[TenantDb],
      kind: TenantDbKind,
      metastore: Map[String, String],
      sent: Map[String, String]
  ): Option[String] =
    val changed = changedServerKeys(stored, kind, metastore, sent)
    Option.when(changed.nonEmpty)(
      s"a superuser sets a database's Postgres server, and this would change ${changed.mkString(", ")}: " +
        s"leave ${ServerKeys.mkString(", ")} out of a create. An update's metastore replaces the " +
        "stored one, so send them back as the row holds them; a password left out is kept."
    )

  /** The one check of what only a superuser chooses for a database, judged for any other writer;
    * None when that writer may. `stored` is the row an update starts from, None on a create, the
    * next three are the row as it would then be, and `sent` is the metastore the writer sent. A
    * superuser, a manifest import, `qod serve`'s provisioning and a branch's registration do not
    * come here, nor does a row an update leaves alone.
    *
    * Which Postgres server it uses: [[serverChoiceError]].
    *
    * Where its data lies, as a path is signed only with the keys the row holds, or a superuser's: a
    * create names no path (QoD places a DuckLake database), or a remote one with own keys
    * ([[holdsOwnKeys]]), and never a `metastore.dataPath`; an `objectStore` goes only with such a
    * path. An update leaves `metastore.dataPath` as it is. An `objectStore` it changes (`sentStore`
    * is what it sent) is a row's own keys: a row holding them complete keeps them complete, and any
    * other value of it changes only with the scheme's secret sent in the same request, so a stored
    * secret goes only with what it was saved with, as a tenant's client secret goes only to its
    * issuer (`TenantSignInWrite`). Any other row, one of managed storage (`managedRow`) included,
    * takes no change.
    */
  def superuserChoiceError(
      stored: Option[TenantDb],
      kind: TenantDbKind,
      dataPath: String,
      metastore: Map[String, String],
      objectStore: Map[String, String],
      sent: Map[String, String],
      sentStore: Map[String, String],
      managedRow: Boolean
  ): Option[String] =
    val chooses =
      "a database's data path is QoD's or a superuser's to choose: leave dataPath out of a " +
        "DuckLake database and QoD places it, or name a folder of a remote store (s3://, s3a://, " +
        "r2://, gs://, az://, azure://, abfss://), not its root, with your own keys in " +
        "objectStore, complete for its scheme"
    val storage = stored match
      case None =>
        val named = metastore.contains("dataPath") ||
          (dataPath.nonEmpty &&
            !(holdsOwnKeys(dataPath, objectStore) && namesRemoteFolder(dataPath)))
        if named then Some(chooses)
        else
          Option.when(objectStore.nonEmpty && !holdsOwnKeys(dataPath, objectStore))(
            "objectStore goes only with a remote dataPath it completes for its scheme: send both, " +
              "or leave objectStore out"
          )
      case Some(td) =>
        val path = if dataPath.nonEmpty then dataPath else td.metastore.getOrElse("dataPath", "")
        // A secret sent is a change whatever it says, and is not compared with the stored one.
        val changed = objectStore != td.objectStore ||
          sentStore.exists((k, v) => isSecretKey(k) && v.nonEmpty)
        if metastore.get("dataPath") != td.metastore.get("dataPath") then Some(chooses)
        else if !changed then None
        else if managedRow || !holdsOwnKeys(path, td.objectStore) then
          Some(
            "a superuser changes this database's objectStore, as it holds no keys of its own: " +
              "leave objectStore out"
          )
        else if !holdsOwnKeys(path, objectStore) then
          Some(
            "this database's remote data path signs with its own keys in objectStore: they can " +
              "change, but stay complete for its scheme. Send them, or leave objectStore out."
          )
        else
          val secret = ownKeyNames(path).toList.flatten.filter(isSecretKey)
          Option.when(!secret.exists(k => sentStore.get(k).exists(_.trim.nonEmpty)))(
            s"a change to this database's objectStore needs its secret, ${secret.mkString(", ")}, " +
              "sent in the same request: send it with the change, or leave objectStore out"
          )
    storage.orElse(serverChoiceError(stored, kind, metastore, sent))

  /** The refusal of a change of a DuckLake row's `dbName`, which names its catalog database for
    * good once the row is made; a superuser's update skips this, and for any other writer
    * [[superuserChoiceError]] answers first. Compared as the effective metastore resolves it: a
    * name left out means the row's own.
    */
  def dbNameChangeError(td: TenantDb, metastore: Map[String, String]): Option[String] =
    val held = td.metastore.getOrElse("dbName", td.name)
    Option.when(td.kind == TenantDbKind.DuckLake && metastore.getOrElse("dbName", td.name) != held)(
      s"dbName of database '${td.name}' cannot be changed: it names the database's catalog. " +
        s"Leave it out, or send the one it holds, '$held'."
    )

  /** The DuckDB catalog alias for a resolved metastore: `catalogAlias` when set, else `dbName`,
    * else `fallback`. Every site that derives the alias from a metastore (USE prelude, author
    * stamp, attached-catalog set, health probe, maintenance alias) must go through here so the
    * branch indirection cannot be missed.
    */
  def catalogAlias(metastore: Map[String, String], fallback: String = ""): String =
    metastore
      .get(CatalogAliasKey)
      .filter(_.nonEmpty)
      .orElse(metastore.get("dbName").filter(_.nonEmpty))
      .getOrElse(fallback)

  private val DuckLakeRequiredKeys: Set[String] =
    Set("pgHost", "pgPort", "pgUser", "pgPassword", "dbName", "schemaName")

  private val DuckDbFileRequiredKeys: Set[String] =
    Set("dbName", "schemaName")

  /** Characters that, if present in `dataPath`, would break out of the single-quoted SQL string
    * literal or chain extra statements inside the node-bootstrap script
    * ([[scripts/spawn-quack-node.sh]] interpolates `dataPath` into `ATTACH '...'` / `DATA_PATH
    * '...'`). `dataPath` is a filesystem/URI path, not an identifier, so a strict identifier
    * allowlist is wrong here; instead we deny the specific metacharacters that enable injection and
    * leave legitimate path characters (slashes, dots, colons, hyphens, underscores) untouched.
    */
  private val DataPathForbiddenChars: Set[Char] = Set('\'', '"', ';', '\\', '\n', '\r')

  /** Characters that would break out of a single-quoted DuckDB literal (or chain extra statements)
    * when `pgHost` / `pgUser` are interpolated verbatim into the `ATTACH 'host=... user=...'`
    * literals and `pgPassword` into the catalog secret's `PASSWORD '...'` in
    * [[scripts/spawn-quack-node.sh]] (the password never rides an ATTACH string). These are
    * free-form connection values (real passwords legitimately carry many symbols), so we deny only
    * the literal-breaking metacharacters rather than imposing an identifier allowlist. A `"` is
    * harmless inside a single-quoted literal, so it is intentionally not denied here (unlike
    * [[DataPathForbiddenChars]]).
    */
  private val ConnParamForbiddenChars: Set[Char] = Set('\'', ';', '\\', '\n', '\r')

  /** `schemaName` is interpolated unquoted into `CREATE SCHEMA` / `USE` in the node-bootstrap
    * script, so it must obey the same Postgres-identifier allowlist as `dbName` (which is validated
    * by [[dbNameError]] just below). A value carrying a space, semicolon, or an injected statement
    * is rejected here at the control-plane trust boundary, protecting both the local and Kubernetes
    * backends at the source.
    */
  private def schemaNameError(metastore: Map[String, String]): Option[String] =
    metastore.get("schemaName") match
      case Some(s) if !Names.isValid(s) =>
        Some(
          s"invalid schemaName '$s': must follow Postgres identifier rules " +
            "(1..63 chars, start with a letter or underscore, only letters, digits, underscore)"
        )
      case _ => None

  /** `dbName` is interpolated into the script both as a double-quoted DuckDB identifier (`ATTACH
    * ... AS "$dbName"`, `USE "$dbName"`) and inside single-quoted connection literals. For DuckLake
    * the supervisor force-sets it to a validated slug, but the DuckDbFile kind (and config-import
    * paths) carry a caller-supplied value, so we pin it to the Postgres-identifier allowlist here
    * to close that hole.
    */
  private def dbNameError(metastore: Map[String, String]): Option[String] =
    metastore.get("dbName") match
      case Some(d) if !Names.isValid(d) =>
        Some(
          s"invalid dbName '$d': must follow Postgres identifier rules " +
            "(1..63 chars, start with a letter or underscore, only letters, digits, underscore)"
        )
      case _ => None

  /** Rejects a single-quoted-literal connection value (`pgHost` / `pgUser` / `pgPassword`) that
    * carries a literal-breaking metacharacter. Absent keys pass (per-kind required-key checks
    * handle presence).
    */
  private def connParamError(metastore: Map[String, String], key: String): Option[String] =
    metastore.get(key) match
      case Some(v) if v.exists(ConnParamForbiddenChars.contains) =>
        Some(
          s"invalid $key: must not contain a single quote, semicolon, backslash, " +
            "or newline/carriage-return character"
        )
      case _ => None

  /** `pgHost` and `pgUser` are written unquoted into a node's connection string, and `pgHost` into
    * the manager's JDBC URL: no connection string reads a value holding whitespace as one value,
    * nor a JDBC URL a host holding `?`, `&`, `=` or `#`.
    */
  private def connStringError(metastore: Map[String, String]): Option[String] =
    if metastore.get("pgUser").exists(breaksConnString) then
      Some("invalid pgUser: must not contain whitespace")
    else if metastore.get("pgHost").exists(h => breaksConnString(h) || h.exists("?&=#".contains))
    then Some("invalid pgHost: must not contain whitespace or any of ? & = #")
    else None

  /** `pgPort` is interpolated into `port=$pgPort` inside a single-quoted connection literal; a
    * non-numeric value could smuggle SQL, so we require it to be all digits.
    */
  private def pgPortError(metastore: Map[String, String]): Option[String] =
    metastore.get("pgPort") match
      case Some(p) if !p.matches("^[0-9]+$") =>
        Some(s"invalid pgPort '$p': must be numeric")
      case _ => None

  private def dataPathError(dataPath: String): Option[String] =
    val bad = dataPath.toSet.intersect(DataPathForbiddenChars)
    if bad.nonEmpty then
      Some(
        "invalid dataPath: must not contain quote, semicolon, backslash, " +
          "or newline/carriage-return characters"
      )
    else None

  /** Rejects a semicolon in any `objectStore` VALUE (keys are unrestricted: they come from a fixed
    * UI vocabulary, not free-form user input). `ObjectStoreSecret.azureSecret` concatenates
    * `azure_account` / `azure_account_key` unescaped into a semicolon-delimited Azure connection
    * string before the whole string is wrapped in a single-quoted literal, so a semicolon smuggled
    * into a value injects extra connection-string fields (e.g. overriding `AccountName`) even
    * though the literal-quoting itself is safe. Public: this is the one place the rule and its
    * message are authored; `ondemand.api.TenantDbHandlers.validateObjectStore` delegates here
    * instead of re-implementing the check.
    */
  def objectStoreError(objectStore: Map[String, String]): Option[String] =
    // A line break is refused too: the values are interpolated into the node's init SQL, where a
    // value spread over lines could fake a line of that SQL (Task 20 review I1).
    objectStore
      .find { case (_, v) => v.contains(";") || v.contains('\n') || v.contains('\r') }
      .map { case (k, _) =>
        s"invalid objectStore value for '$k': must not contain ';' or a line break"
      }

  /** Injection-safety checks only: reject any script-interpolated value that carries a
    * SQL-literal-breaking or identifier-breaking metacharacter, validating each field only when it
    * is present. This is the security-critical subset of [[validate]] and is safe to run on paths
    * where the required metastore keys are supplied later from the default metastore (e.g. config
    * import), so it does NOT enforce key presence.
    */
  private def catalogAliasError(metastore: Map[String, String]): Option[String] =
    metastore.get(CatalogAliasKey) match
      case Some(a) if !Names.isValid(a) =>
        Some(
          s"invalid catalogAlias '$a': must follow Postgres identifier rules " +
            "(1..63 chars, start with a letter or underscore, only letters, digits, underscore)"
        )
      case _ => None

  /** The encryption contract, checked for every kind. `encryptionKey` is interpolated into a
    * single-quoted DuckDB string literal by the spawn scripts, exactly like `pgPassword`, so it
    * obeys the same metacharacter denial.
    */
  private def encryptionError(td: TenantDb): Option[String] =
    val key = td.metastore.get(EncryptionKeyName).filter(_.nonEmpty)
    if td.encrypted && td.kind == TenantDbKind.InMemory then
      Some("kind=memory cannot be encrypted: nothing is stored at rest")
    else if key.isDefined && td.kind == TenantDbKind.DuckLake then
      Some("kind=ducklake manages its own per-file encryption keys: remove encryptionKey")
    else if key.isDefined && !td.encrypted then
      Some("encryptionKey requires encrypted=true: one intent per call")
    else if key.exists(_.exists(ConnParamForbiddenChars.contains)) then
      Some("invalid encryptionKey: must not contain any of ' ; \\ or a newline")
    else None

  def validateSafety(td: TenantDb): Option[String] =
    encryptionError(td)
      .orElse(schemaNameError(td.metastore))
      .orElse(dbNameError(td.metastore))
      .orElse(catalogAliasError(td.metastore))
      .orElse(connParamError(td.metastore, "pgHost"))
      .orElse(connParamError(td.metastore, "pgUser"))
      .orElse(connParamError(td.metastore, "pgPassword"))
      .orElse(connStringError(td.metastore))
      .orElse(pgPortError(td.metastore))
      .orElse(dataPathError(td.dataPath))
      .orElse(objectStoreError(td.objectStore))

  /** Required metastore keys per kind. Used by both [[validate]] (presence check at create time)
    * and [[ai.starlake.quack.ondemand.PoolSupervisor.updateTenantDb]] (drop guard at update time)
    * so both share one source of truth.
    */
  def requiredMetastoreKeys(kind: TenantDbKind): Set[String] = kind match
    case TenantDbKind.DuckLake   => DuckLakeRequiredKeys
    case TenantDbKind.DuckDbFile => DuckDbFileRequiredKeys
    case TenantDbKind.InMemory   => Set.empty

  /** Metastore keys whose manager defaults can satisfy a sparse DuckLake row. Whitespace-only
    * values are configuration noise, not usable credentials.
    */
  def defaultedMetastoreKeys(defaults: Map[String, String]): Set[String] =
    defaults.collect { case (key, value) if value.trim.nonEmpty => key }.toSet

  /** Returns Some(error) if the value violates its per-kind contract. Enforces required-key
    * presence AND injection safety; use on the REST createTenantDb path where the metastore is
    * supplied inline. `defaults` is the manager's `quack-on-demand.defaultMetastore` map: a
    * required DuckLake key missing from the request but carrying a non-empty value in what the row
    * inherits of `defaults` ([[inheritedDefaults]]) is satisfied there, because the runtime merge
    * ([[ai.starlake.quack.ondemand.PoolSupervisor.effectiveMetastoreFor]]) fills it at spawn time.
    * Only the DuckLake arm consults `defaults`; duckdb-file keeps requiring its keys inline, and
    * injection safety always runs on the supplied values.
    */
  def validate(td: TenantDb, defaults: Map[String, String] = Map.empty): Option[String] =
    td.kind match {
      case TenantDbKind.DuckLake =>
        // A value that breaks a connection string reads as another server, whose login would then
        // be missing: it says what it is first.
        validateSafety(td).orElse {
          val defaulted = defaultedMetastoreKeys(inheritedDefaults(td.metastore, defaults))
          val missing   = requiredMetastoreKeys(td.kind) -- td.metastore.keySet -- defaulted
          if missing.nonEmpty then
            Some(s"kind=ducklake requires metastore keys ${missing.mkString(", ")}")
          else Option.when(td.dataPath.isEmpty)("kind=ducklake requires non-empty dataPath")
        }

      case TenantDbKind.DuckDbFile =>
        val missing = requiredMetastoreKeys(td.kind) -- td.metastore.keySet
        if missing.nonEmpty then
          Some(s"kind=duckdb-file requires metastore keys ${missing.mkString(", ")}")
        else if td.dataPath.isEmpty then Some("kind=duckdb-file requires non-empty dataPath")
        else validateSafety(td)

      case TenantDbKind.InMemory =>
        // `dataPath` is permitted (and optional) here, but it is NOT a catalog: the spawn
        // script's `memory)` arm never ATTACHes it and never mkdirs it. It exists solely as the
        // SCOPE for the per-database object-store secret
        // ([[ai.starlake.quack.ondemand.runtime.ObjectStoreSecret.sql]] derives the scope from
        // dataPath and emits nothing without one), which is what lets a memory database of views
        // over remote parquet actually read. Injection safety still applies via validateSafety.
        if td.metastore.nonEmpty then Some("kind=memory requires empty metastore")
        else validateSafety(td)
    }
}
