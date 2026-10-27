package ai.starlake.quack.model

/** One of a user's own S3 credentials (in-engine authorization, decision 23;
  * `qodstate_user_s3_credential`, Liquibase 0052): the keys the user reaches S3 locations under
  * `scope` with, and nothing else does. `scope` is stored as [[UserS3CredentialRules]] normalized
  * it, `s3://<bucket>/` or `s3://<bucket>/<prefix>/`; `endpoint` is an entry of the operator's
  * `QOD_USER_S3_ALLOWED_ENDPOINTS` (`host[:port]` for TLS, `http://host[:port]` for plain HTTP),
  * None for AWS's own. A node receives the row only for that user's connections, as the connection
  * secret [[secretName]]. Times are epoch milliseconds; `updatedAt` moves on every replace.
  */
final case class UserS3Credential(
    id: String,
    userId: String,
    scope: String,
    accessKeyId: String,
    secretAccessKey: String,
    sessionToken: Option[String],
    region: Option[String],
    endpoint: Option[String],
    urlStyle: Option[String],
    createdAt: Long,
    updatedAt: Long
):
  /** Prints the secret and the session token as [[FederatedSecret.RedactedMarker]], so no log line
    * built from a credential carries either. Equality and `copy` are untouched.
    */
  override def toString: String =
    s"UserS3Credential(id=$id,userId=$userId,scope=$scope,accessKeyId=$accessKeyId," +
      s"secretAccessKey=${FederatedSecret.RedactedMarker}," +
      s"sessionToken=${sessionToken.map(_ => FederatedSecret.RedactedMarker)},region=$region," +
      s"endpoint=$endpoint,urlStyle=$urlStyle,createdAt=$createdAt,updatedAt=$updatedAt)"

  /** The node's connection secret for this credential. */
  def secretName: String = UserS3Credential.secretName(id)

  /** Whether the node talks TLS to the endpoint: false only for a plain `http://` one. */
  def useSsl: Boolean = !endpoint.exists(_.startsWith("http://"))

  /** The endpoint as DuckDB's `ENDPOINT` takes it, `host[:port]`; empty for AWS's own. */
  def endpointHost: String = endpoint.fold("")(_.stripPrefix("http://"))

object UserS3Credential:

  /** `qod_uc_<first 8 of id>`: a user's ids differ in their first 8
    * ([[UserS3CredentialRules.newId]]).
    */
  def secretName(id: String): String = s"qod_uc_${id.take(8)}"

  /** Credentials one user may hold: every one rides each credentials fetch of that user's
    * connections.
    */
  val MaxPerUser: Int = 32
