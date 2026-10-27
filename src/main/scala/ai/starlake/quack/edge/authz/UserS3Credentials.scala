package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.UserS3Credential
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json

import scala.util.control.NonFatal

/** Each user's own S3 credentials (in-engine authorization, decision 23), as a replica serves them:
  * every session handle carries [[digest]] of its principal's set (`creds`), a node fetches the set
  * from the replica its handle names ([[CredentialsRoute]]) when that digest differs from the one
  * its connection secrets were made from, and the location rules admit a user's `s3://` URLs under
  * the scopes of [[current]].
  */
trait UserS3Credentials:

  /** `userId`'s credentials as last loaded, whether or not they can be served now. */
  def of(userId: String): List[UserS3Credential]

  /** The digest of the set [[current]] serves (a handle's `creds`). */
  def digest(userId: String): String

  /** `userId`'s credentials as this replica serves them now; Left (why) when it cannot vouch for
    * them (the store could not be read since a change), which a caller answers as unavailable.
    */
  def current(userId: String): Either[String, List[UserS3Credential]] = Right(of(userId))

object UserS3Credentials:

  /** No user holds any credential: for components a test builds without a control plane. */
  val none: UserS3Credentials = new UserS3Credentials:
    def of(userId: String): List[UserS3Credential] = Nil
    def digest(userId: String): String             = ""

  /** What [[digestOf]] hashes: the JSON array of `[id, updatedAt]` pairs, by id. */
  def digestJson(creds: List[UserS3Credential]): Json =
    Json.arr(
      creds.sortBy(_.id).map(c => Json.arr(Json.fromString(c.id), Json.fromLong(c.updatedAt)))*
    )

  /** A handle's `creds`: "" for no credential, else the lowercase hex SHA-256 of the canonical
    * [[digestJson]]. Any added, removed or replaced credential changes it (a replace moves
    * `updatedAt`).
    */
  def digestOf(creds: List[UserS3Credential]): String =
    if creds.isEmpty then "" else CanonicalJson.sha256Hex(digestJson(creds))

/** [[UserS3Credentials]] over a loader, refreshed by the supervisor together with the policy
  * versions (after every local RBAC mutation, credential writes included, and on every restore,
  * which HA peers run on the rbac NOTIFY). A failed refresh marks the cache stale: [[current]]
  * answers Left until a refresh succeeds, so a credential removed meanwhile is never served or
  * admitted from the last sets. While stale, every read retries the load, at most once per
  * [[UserS3CredentialCache.RetryMs]]. Never logs a credential.
  */
final class UserS3CredentialCache(
    load: () => List[UserS3Credential],
    clock: () => Long = () => System.currentTimeMillis()
) extends UserS3Credentials
    with LazyLogging:

  @volatile private var byUser: Map[String, (List[UserS3Credential], String)] = Map.empty
  @volatile private var stale: Boolean                                        = false
  @volatile private var lastAttempt: Long                                     = 0L

  def of(userId: String): List[UserS3Credential] = byUser.get(userId).fold(Nil)(_._1)

  def digest(userId: String): String =
    retryIfStale()
    byUser.get(userId).fold("")(_._2)

  override def current(userId: String): Either[String, List[UserS3Credential]] =
    retryIfStale()
    if stale then Left(UserS3CredentialCache.StaleReason) else Right(of(userId))

  def refresh(): Unit = synchronized {
    lastAttempt = clock()
    try
      byUser = load().groupBy(_.userId).map { (user, creds) =>
        user -> (creds.sortBy(_.scope), UserS3Credentials.digestOf(creds))
      }
      if stale then logger.info("user S3 credentials: reloaded after a failed refresh")
      stale = false
    catch
      case NonFatal(e) =>
        stale = true
        logger.warn(
          "user S3 credentials: refresh failed, serving none until a reload succeeds: " +
            e.getClass.getSimpleName
        )
  }

  private def retryIfStale(): Unit =
    if stale && clock() - lastAttempt >= UserS3CredentialCache.RetryMs then
      synchronized(
        if stale && clock() - lastAttempt >= UserS3CredentialCache.RetryMs then refresh()
      )

object UserS3CredentialCache:

  /** How often a stale cache retries its load, at most. */
  val RetryMs: Long = 1_000L

  /** Why a stale cache serves nothing. */
  val StaleReason: String = "the users' S3 credentials could not be read"

/** The credentials a replica may serve: [[UserS3Credentials]] `base` without any credential whose
  * scope now lies at or under a database data folder ([[location.LocationChecker.scopeError]]
  * against the live deny set). A scope is checked when it is added, but a database created
  * afterwards at that scope or at an ancestor of it (a DuckLake database with no data path of its
  * own lands at `parent(default)/<name>`) would otherwise let the user's keys sign that database's
  * files. [[current]] is Left while either the base or the deny set cannot be read; [[digest]] is
  * the served set's, so a handle names exactly what the credentials endpoint answers. Each withheld
  * credential is named once in a WARN (its user and id, never a key).
  */
final class ServedUserS3Credentials(
    base: UserS3Credentials,
    dataFolders: () => Either[String, List[String]]
) extends UserS3Credentials
    with LazyLogging:

  private val withheld = scala.collection.concurrent.TrieMap.empty[String, Unit]

  def of(userId: String): List[UserS3Credential] = current(userId).getOrElse(Nil)

  def digest(userId: String): String =
    current(userId).fold(_ => base.digest(userId), UserS3Credentials.digestOf)

  override def current(userId: String): Either[String, List[UserS3Credential]] =
    for
      set     <- base.current(userId)
      folders <- if set.isEmpty then Right(Nil) else dataFolders()
    yield set.filter(admitted(_, folders))

  private def admitted(c: UserS3Credential, folders: List[String]): Boolean =
    location.LocationChecker.scopeError(c.scope, folders) match
      case None =>
        withheld.remove(c.id)
        true
      case Some(why) =>
        if withheld.putIfAbsent(c.id, ()).isEmpty then
          logger.warn(
            s"user S3 credentials: withholding credential ${c.id} of user ${c.userId}: $why"
          )
        false
