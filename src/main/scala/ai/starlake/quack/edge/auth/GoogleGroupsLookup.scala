package ai.starlake.quack.edge.auth

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import com.typesafe.scalalogging.LazyLogging

import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.{Base64, Date}

/** Fetches Google Workspace group memberships for a user via the Directory API.
  *
  * Requires a service account JSON key file with:
  *   - Domain-wide delegation enabled
  *   - `https://www.googleapis.com/auth/admin.directory.group.readonly` scope
  *   - An admin email for impersonation (the service account acts as this admin)
  *
  * The service account key JSON must contain: client_email, private_key, token_uri.
  */
class GoogleGroupsLookup(
    serviceAccountKeyPath: String,
    cacheTtlSeconds: Long = 300
) extends AutoCloseable,
      LazyLogging:

  /** Caffeine-backed cache of resolved Google group memberships per user email. Caffeine handles
    * the TTL (`expireAfterWrite`) and bounds memory (`maximumSize`) so historical users
    * accumulated over the manager's lifetime can't leak. The previous hand-rolled
    * ConcurrentHashMap + fetchedAt form did neither.
    */
  private val groupsCache: com.github.benmanes.caffeine.cache.Cache[String, Set[String]] =
    com.github.benmanes.caffeine.cache.Caffeine
      .newBuilder()
      .expireAfterWrite(java.time.Duration.ofSeconds(cacheTtlSeconds))
      .maximumSize(10_000L)
      .build[String, Set[String]]()

  private val httpClient = HttpClient
    .newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  private val (clientEmail, privateKey, tokenUri) = loadServiceAccountKey()

  @volatile private var cachedAccessToken: String = ""
  @volatile private var tokenExpiry: Instant      = Instant.EPOCH

  /** Fetch group email addresses for a given user email. Results are cached per email for
    * cacheTtlSeconds. Returns an empty set on failure (non-blocking).
    */
  def getGroupsForUser(userEmail: String): Set[String] =
    val key    = userEmail.toLowerCase
    val cached = groupsCache.getIfPresent(key)
    if cached != null then cached
    else
      val groups = fetchGroups(key)
      groupsCache.put(key, groups)
      groups

  private def fetchGroups(userEmail: String): Set[String] =
    try
      val token   = getAccessToken()
      val url     = s"https://admin.googleapis.com/admin/directory/v1/groups?userKey=$userEmail"
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .header("Authorization", s"Bearer $token")
        .GET()
        .timeout(java.time.Duration.ofSeconds(10))
        .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() == 200 then
        val groups = parseGroupEmails(response.body())
        logger.debug(s"Fetched ${groups.size} groups for $userEmail from Google Directory API")
        groups
      else
        logger.warn(
          s"Google Directory API returned ${response.statusCode()} for $userEmail: ${response.body()}"
        )
        Set.empty
    catch
      case e: Exception =>
        logger.warn(s"Failed to fetch Google groups for $userEmail: ${e.getMessage}")
        Set.empty

  override def close(): Unit = ()

  /** Get a valid access token, refreshing if expired. */
  private def getAccessToken(): String = synchronized {
    if cachedAccessToken.nonEmpty && Instant.now().isBefore(tokenExpiry) then
      return cachedAccessToken

    val now = Instant.now()
    val jwt = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
      new JWTClaimsSet.Builder()
        .issuer(clientEmail)
        .claim("scope", "https://www.googleapis.com/auth/admin.directory.group.readonly")
        .audience(tokenUri)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build()
    )
    jwt.sign(new RSASSASigner(privateKey))

    val body =
      s"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt.serialize()}"
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(tokenUri))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .timeout(java.time.Duration.ofSeconds(10))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then
      throw new RuntimeException(
        s"Token request failed: ${response.statusCode()} ${response.body()}"
      )

    cachedAccessToken = extractJsonField(response.body(), "access_token")
      .getOrElse(throw new RuntimeException("No access_token in response"))
    tokenExpiry = now.plusSeconds(3500) // refresh 100s before actual expiry
    cachedAccessToken
  }

  /** Parse group emails from Directory API JSON response. */
  private def parseGroupEmails(json: String): Set[String] =
    // Extract all "email" values from the "groups" array
    val pattern = """"email"\s*:\s*"([^"]+)"""".r
    pattern.findAllMatchIn(json).map(_.group(1).toLowerCase).toSet

  private def loadServiceAccountKey(): (String, RSAPrivateKey, String) =
    val content =
      new String(Files.readAllBytes(new File(serviceAccountKeyPath).toPath), StandardCharsets.UTF_8)

    val email = extractJsonField(content, "client_email")
      .getOrElse(throw new RuntimeException("client_email not found in service account key"))
    val keyPem = extractJsonField(content, "private_key")
      .getOrElse(throw new RuntimeException("private_key not found in service account key"))
      .replace("\\n", "\n")
    val uri = extractJsonField(content, "token_uri")
      .getOrElse("https://oauth2.googleapis.com/token")

    val stripped = keyPem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replaceAll("\\s", "")
    val decoded = Base64.getDecoder.decode(stripped)
    val spec    = new PKCS8EncodedKeySpec(decoded)
    val key     = KeyFactory.getInstance("RSA").generatePrivate(spec).asInstanceOf[RSAPrivateKey]

    logger.info(s"Google Directory API service account loaded: $email")
    (email, key, uri)

  private def extractJsonField(json: String, field: String): Option[String] =
    val pattern = s""""$field"\\s*:\\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))
