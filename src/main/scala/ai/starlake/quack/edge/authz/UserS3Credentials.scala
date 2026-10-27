package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.UserS3Credential
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json

import scala.util.control.NonFatal

/** Each user's own S3 credentials (in-engine authorization, decision 23), as a replica serves them:
  * every session handle carries [[digest]] of its principal's set (`creds`), a node fetches the set
  * from the replica its handle names ([[CredentialsRoute]]) when that digest differs from the one
  * its connection secrets were made from, and the location rules admit a user's `s3://` URLs under
  * the scopes of [[of]].
  */
trait UserS3Credentials:
  def of(userId: String): List[UserS3Credential]
  def digest(userId: String): String

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
  * which HA peers run on the rbac NOTIFY). A failed refresh keeps the last sets: the mutation that
  * asked for it has committed, and a stale set only delays a node's refetch to the next refresh.
  * Never logs a credential.
  */
final class UserS3CredentialCache(load: () => List[UserS3Credential])
    extends UserS3Credentials
    with LazyLogging:

  @volatile private var byUser: Map[String, (List[UserS3Credential], String)] = Map.empty

  def of(userId: String): List[UserS3Credential] = byUser.get(userId).fold(Nil)(_._1)

  def digest(userId: String): String = byUser.get(userId).fold("")(_._2)

  def refresh(): Unit =
    try
      byUser = load().groupBy(_.userId).map { (user, creds) =>
        user -> (creds.sortBy(_.scope), UserS3Credentials.digestOf(creds))
      }
    catch
      case NonFatal(e) =>
        logger.warn(
          s"user S3 credentials: refresh failed, keeping the last sets: ${e.getClass.getSimpleName}"
        )
