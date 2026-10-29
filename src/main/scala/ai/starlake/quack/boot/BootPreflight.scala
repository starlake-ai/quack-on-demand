package ai.starlake.quack.boot

import java.util.Locale
import ai.starlake.quack.AdminConfig
import ai.starlake.quack.{ManagementAuthConfig, ManagerConfig, NodeAuthzConfig, RuntimeType}
import ai.starlake.quack.edge.auth.{AuthQueryPreconditions, AuthenticationService}
import ai.starlake.quack.edge.config.{AuthenticationConfig, DatabaseAuthConfig}
import ai.starlake.quack.model.{FederatedAlias, Names}
import ai.starlake.quack.ondemand.state.{
  EmailFormat,
  FederatedSourceOps,
  SessionSecretStore,
  UserStore
}
import com.typesafe.scalalogging.LazyLogging

import javax.sql.DataSource

/** Boot-time gates and seeding that must run before the manager wires its components. Extracted
  * from Main.bootManager; every check keeps its original fail-fast behavior (sys.error aborts the
  * boot with a clean config-error framing instead of a raw stack trace).
  */
object BootPreflight extends LazyLogging:

  /** The refusal for a removed static API key, verbatim in the boot error. */
  val RemovedApiKeyMessage: String =
    "QOD_API_KEY (quack-on-demand.apiKey) was removed: the manager no longer accepts a static " +
      "API key. Use a personal access token (qod auth pat create) or a qod login session, and " +
      "unset QOD_API_KEY."

  /** Pure gate: the static API key was removed. Refuses when the environment holds a non-empty
    * (after trimming) `QOD_API_KEY`, or when the loaded configuration holds a non-empty
    * `quack-on-demand.apiKey` (an operator's own config file, or a `-D` system property). An empty
    * value passes: older Compose files render `API_KEY=` as one. Both are read directly because,
    * with the field gone, pureconfig would ignore the key silently and the manager would boot
    * without the credential its operator relies on. `Main.bootManager` runs it first, before HA
    * validation and before any I/O.
    */
  def checkRemovedApiKey(
      env: String => Option[String],
      raw: com.typesafe.config.Config
  ): Either[String, Unit] =
    val path     = "quack-on-demand.apiKey"
    val inEnv    = env("QOD_API_KEY").exists(_.trim.nonEmpty)
    val inConfig = raw.hasPath(path) && raw.getValue(path).unwrapped().toString.trim.nonEmpty
    if inEnv || inConfig then Left(RemovedApiKeyMessage) else Right(())

  /** The refusal for a FlightSQL edge with no authenticator, verbatim in the boot error. */
  val NoEdgeAuthMessage: String =
    "the FlightSQL edge and the Quack front door have no authenticator: QOD_AUTH_DB_ENABLED=false " +
      "and no other quack-flightsql.auth provider is configured, so no FlightSQL or Quack client " +
      "could ever connect. " +
      "System-scope OIDC (QOD_MGMT_OIDC_*) signs in to the admin UI and REST only, never to " +
      "FlightSQL. Set QOD_AUTH_DB_ENABLED=true, or a Keycloak or Azure password backend " +
      "(QOD_AUTH_KEYCLOAK_ENABLED=true or QOD_AUTH_AZURE_ENABLED=true, with their settings), or " +
      "a bearer provider: an external JWT issuer (JWT_SECRET_KEY or JWT_PUBLIC_KEY_PATH), " +
      "QOD_AUTH_GOOGLE_ENABLED=true or QOD_AUTH_AWS_ENABLED=true."

  /** Pure gate: the FlightSQL edge needs an authenticator. With an empty `quack-flightsql.auth`
    * chain (no basic and no bearer provider) the edge handshake refuses every client, so no
    * FlightSQL or Quack client could connect; system-scope OIDC adds no edge provider, so it does
    * not count. The FlightSQL edge has no enable switch and always starts, so the check holds
    * whether or not the Quack front door (`QOD_QUACK_ENABLED`) runs too. It reads the
    * configuration, not a built [[AuthenticationService]], so `Main.bootManager` runs it right
    * after the removed-key refusal, at the top of `bootManager`, before it opens any socket and
    * before HA validation.
    */
  def checkEdgeAuthAvailable(auth: AuthenticationConfig): Either[String, Unit] =
    if AuthenticationService.configuresProvider(auth) then Right(()) else Left(NoEdgeAuthMessage)

  /** Pure gate: something must be able to create a session. Refuses when the authentication service
    * has no password provider (its basic chain is empty, as with `QOD_AUTH_DB_ENABLED=false` and no
    * Keycloak or Azure password backend) AND system-scope OIDC sign-in is not served: nothing could
    * create a session, a personal access token is minted with one, and `/api` would be unreachable.
    * `Main.bootManager` runs it right after the edge check, from the configuration alone
    * (`AuthenticationService.configuresPasswordProvider`). System-scope OIDC counts exactly as
    * `ManagementAuthWiring` serves it: `identitySource=oidc` with the issuer URL and the client id
    * set (under `db` the system scope gets no OIDC client, whatever `QOD_MGMT_OIDC_*` hold). Tenant
    * OIDC does not count: it is configured through `/api` and never yields a superuser.
    */
  def checkLoginAvailable(
      hasPasswordProvider: Boolean,
      mgmt: ManagementAuthConfig
  ): Either[String, Unit] =
    val systemOidc =
      mgmt.identitySource.trim.toLowerCase(Locale.ROOT) == "oidc" &&
        mgmt.oidc.issuerUrl.trim.nonEmpty && mgmt.oidc.clientId.trim.nonEmpty
    if !hasPasswordProvider && !systemOidc then
      Left(
        "nothing can sign in to this manager: the authentication service has no password " +
          "provider (QOD_AUTH_DB_ENABLED=false and no Keycloak or Azure password backend) and " +
          "system-scope OIDC sign-in is not configured (it needs QOD_MGMT_IDENTITY_SOURCE=oidc " +
          "with QOD_MGMT_OIDC_ISSUER_URL and QOD_MGMT_OIDC_CLIENT_ID set). Without a session no " +
          "personal access token can be minted, so /api would be unreachable. Set " +
          "QOD_AUTH_DB_ENABLED=true, or set QOD_MGMT_IDENTITY_SOURCE=oidc with " +
          "QOD_MGMT_OIDC_ISSUER_URL, QOD_MGMT_OIDC_CLIENT_ID and QOD_MGMT_OIDC_CLIENT_SECRET."
      )
    else Right(())

  /** Resolve the session JWT signing secret ([[SessionSecretStore]]): a configured
    * `QOD_SESSION_JWT_SECRET` wins and `controlPlane` is not touched; an unset (or blank) one is
    * the control plane's, created by the first boot, so sessions survive a restart and every HA
    * replica signs with the same value. `Main.bootManager` runs it right after the Liquibase apply
    * (the table must exist) and before anything reads the secret. Prints one line naming the
    * source, never the value, deliberately NOT through the logger, whose default ERROR root level
    * would swallow an info line.
    */
  def withSessionSecret(cfg: ManagerConfig, controlPlane: => DataSource): ManagerConfig =
    val resolved =
      SessionSecretStore.resolve(cfg.auth.management.sessionJwtSecret, controlPlane)
    println(sessionSecretLine(resolved.source))
    if resolved.source == SessionSecretStore.Source.Configured then cfg
    else
      cfg.copy(
        auth = cfg.auth.copy(
          management = cfg.auth.management.copy(sessionJwtSecret = resolved.secret)
        )
      )

  /** The boot line naming where the session secret came from. */
  def sessionSecretLine(source: SessionSecretStore.Source): String = source match
    case SessionSecretStore.Source.Configured =>
      "session secret: from QOD_SESSION_JWT_SECRET (quack-on-demand.auth.management.sessionJwtSecret)"
    case SessionSecretStore.Source.Created =>
      "session secret: generated and stored in the control plane (set QOD_SESSION_JWT_SECRET " +
        "to pin one)"
    case SessionSecretStore.Source.Stored =>
      "session secret: stored in the control plane (set QOD_SESSION_JWT_SECRET to pin one)"

  /** Cheap startup gate: when database auth is enabled, systemQuery/tenantQuery must each project
    * (password_hash, kind, enabled, must_change_password) -- exactly the shape
    * DatabaseAuthenticator requires at runtime now that the tolerant short-projection branch is
    * gone. Caught here instead of at first login. Runs AFTER the Liquibase apply: the default
    * queries target qodstate_user in the control-plane database, which does not exist yet on a
    * fresh (or nuked) metastore until the changelog has been applied. The probe result is computed
    * first and sys.error'd after, so an unreachable auth database surfaces as the same clean
    * config-error framing as the sibling boot gates, not a raw JDBC exception.
    */
  def probeAuthDatabase(dbCfg: DatabaseAuthConfig): Unit =
    val probeResult: Either[String, Unit] =
      try
        Class.forName("org.postgresql.Driver")
        val probeConn = java.sql.DriverManager.getConnection(
          dbCfg.jdbcUrl,
          dbCfg.username,
          dbCfg.password
        )
        try AuthQueryPreconditions.validate(probeConn, dbCfg)
        finally probeConn.close()
      catch
        case e: Exception =>
          Left(
            "auth.database startup validation failed: could not probe " +
              s"systemQuery/tenantQuery against '${dbCfg.jdbcUrl}' " +
              s"(${e.getMessage}). Check QOD_AUTH_DB_JDBC_URL / QOD_AUTH_DB_USER / " +
              "QOD_AUTH_DB_PASSWORD and that the auth database is reachable."
          )
    probeResult.left.foreach(msg => sys.error(msg))

  /** Probe the CONTROL-PLANE database (the `defaultMetastore` URL that `UserStore` enforces
    * against) for the lockout columns. Lockout state lives on `qodstate_user` in the control-plane
    * db, NOT the auth db, so this must probe the same URL UserStore uses -- probing the auth db
    * would be untruthful when they diverge (see `checkLockoutDbCoherence`). A control-plane db that
    * never ran changelog 0029/0030 would otherwise fail at first login instead of at boot.
    */
  def probeLockoutColumns(controlPlaneUrl: String, user: String, password: String): Unit =
    val probeResult: Either[String, Unit] =
      try
        Class.forName("org.postgresql.Driver")
        val probeConn = java.sql.DriverManager.getConnection(controlPlaneUrl, user, password)
        try checkLockoutColumns(probeConn)
        finally probeConn.close()
      catch
        case e: Exception =>
          Left(
            "auth.lockout.enabled is true but the control-plane database could not be probed for " +
              s"lockout columns at '$controlPlaneUrl' (${e.getMessage}). Check the " +
              "quack-on-demand.defaultMetastore settings and that the database is reachable."
          )
    probeResult.left.foreach(msg => sys.error(msg))

  /** Pure gate: lockout state (`failed_attempts` / `locked_at`) is read and written by `UserStore`
    * against `qodstate_user` in the CONTROL-PLANE database (the `defaultMetastore`), but auth
    * queries run against the auth database (`QOD_AUTH_DB_JDBC_URL`). In the default deployment the
    * two URLs coincide (the auth URL is derived from the defaultMetastore). If an operator points
    * the auth db at a DIFFERENT database, lockout enforcement becomes inert -- writes hit 0 rows on
    * the auth side and `isLocked` is always false, i.e. the control fails OPEN while boot claims it
    * is enabled. Rather than fail open on a security control, refuse to start. URLs are compared
    * trim-normalized; the two default URLs are built by identical string interpolation, so exact
    * equality holds for the coincident case. Side-effect-free so it is unit-testable; the call site
    * sys.errors on Left, mirroring `checkLockoutSmtp`.
    */
  def checkLockoutDbCoherence(
      lockoutEnabled: Boolean,
      controlPlaneUrl: String,
      authUrl: String
  ): Either[String, Unit] =
    if lockoutEnabled && controlPlaneUrl.trim != authUrl.trim then
      Left(
        "auth.lockout.enabled is true but the auth database URL differs from the control-plane " +
          "database. Account lockout enforces against qodstate_user in the control-plane database " +
          s"('$controlPlaneUrl'), while auth queries run against '$authUrl' -- with divergent URLs " +
          "lockout is inert (writes hit 0 rows, isLocked is always false, so lockout fails open). " +
          "Point QOD_AUTH_DB_JDBC_URL at the control-plane database, or set " +
          "QOD_AUTH_LOCKOUT_ENABLED=false."
      )
    else Right(())

  /** When lockout is enabled, `qodstate_user` must carry `failed_attempts`, `locked_at`, and
    * `email` -- the three columns Task 9's enforcement reads and writes. A control-plane database
    * that never ran the quack-on-demand Liquibase changelog (or an operator-managed lookalike
    * table) would otherwise fail at first login instead of at boot.
    */
  private val LockoutRequiredColumns = Set("failed_attempts", "locked_at", "email")

  private def checkLockoutColumns(conn: java.sql.Connection): Either[String, Unit] =
    val rs      = conn.getMetaData.getColumns(null, null, "qodstate_user", null)
    val present = scala.collection.mutable.Set.empty[String]
    try while rs.next() do present += rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT)
    finally rs.close()
    val missing = LockoutRequiredColumns.diff(present.toSet)
    if missing.nonEmpty then
      Left(
        "auth.lockout.enabled is true but qodstate_user is missing column(s): " +
          s"${missing.toList.sorted.mkString(", ")} -- run the quack-on-demand Liquibase " +
          "changelog (0029-user-email / 0030-user-lockout) against the control-plane database, " +
          "or set QOD_AUTH_LOCKOUT_ENABLED=false."
      )
    else Right(())

  /** Pure gate: lockout requires a reachable SMTP relay, otherwise a locked-out user has no
    * self-service way back in (the whole reason Phase 1 built the forgot/reset-password flow). Kept
    * side-effect-free so it is unit-testable without a live Postgres or SMTP relay; the call site
    * sys.errors on Left, mirroring `probeAuthDatabase`.
    */
  def checkLockoutSmtp(lockoutEnabled: Boolean, smtpHost: Option[String]): Either[String, Unit] =
    if lockoutEnabled && smtpHost.forall(_.trim.isEmpty) then
      Left(
        "auth.lockout.enabled is true but no SMTP relay is configured -- a locked-out user would " +
          "have no way back in. Set QOD_SMTP_HOST (and QOD_SMTP_PORT / QOD_SMTP_USER / " +
          "QOD_SMTP_PASSWORD as needed) or set QOD_AUTH_LOCKOUT_ENABLED=false."
      )
    else Right(())

  val InternalUrlFormatMessage: String =
    "QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl) is not a plain http:// URL with " +
      "a host and no user info, query or fragment: nodes call this replica's decision endpoint " +
      "on it, and qod-node speaks plain HTTP only"

  /** The format refusal when QOD_INTERNAL_URL is unset and the URL came from QOD_POD_IP. */
  val InternalUrlPodIpFormatMessage: String =
    "QOD_POD_IP (quack-on-demand.nodeAuthz.podIp) does not make a plain http:// URL with a host: " +
      "with QOD_INTERNAL_URL unset, nodes call this replica's decision endpoint on " +
      "http://<pod IP>:<manager port>. Set QOD_POD_IP to this pod's IP (the downward API's " +
      "status.podIP, as the Helm chart does), or set QOD_INTERNAL_URL"

  /** The format refusal when neither is set and the URL came from the bind address. */
  val InternalUrlBindHostFormatMessage: String =
    "QOD_ON_DEMAND_HOST (quack-on-demand.host) does not make a plain http:// URL with a host: " +
      "with QOD_INTERNAL_URL and QOD_POD_IP unset, nodes call this replica's decision endpoint " +
      "on http://<bind address>:<manager port>. Set QOD_INTERNAL_URL"

  def internalUrlKubernetesMessage(url: String): String =
    s"the node decide URL is $url, a loopback address no node pod can reach on the kubernetes " +
      "runtime: leave QOD_INTERNAL_URL unset and set QOD_POD_IP from the downward API (the Helm " +
      "chart does), or set QOD_INTERNAL_URL to this replica's own pod address"

  val InternalUrlFleetMessage: String =
    "fleet mode requires QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl): this " +
      "replica's own plain http:// address on the private network the fleet servers share, one " +
      "per replica (never a load balancer or an address several replicas share, which would " +
      "send a node's verdict calls to a replica that did not mint its handle); every node calls " +
      "it for its statements' verdicts"

  /** The boot line naming the URL nodes call (never a secret: user info and queries are refused).
    */
  def decideUrlLine(url: String): String = s"node decide URL: $url"

  /** Pure gate on the decide URL (`NodeAuthzConfig.replicaUrl`): every node calls it for its
    * statements' verdicts, so a boot whose nodes could not reach it would answer every user
    * statement "authorization unavailable". In fleet mode set explicitly (a loopback one is fine on
    * a single-host fleet); on every runtime a URL qod-node accepts ([[nodeUrlHost]]); on Kubernetes
    * never a loopback or unspecified host (the default included: the chart sets QOD_POD_IP). Right
    * carries the URL; a format refusal never echoes it, since it could carry credentials, and names
    * the setting it came from. Reads configuration only: no name is ever looked up.
    */
  def checkInternalUrl(
      runtimeType: String,
      nodeAuthz: NodeAuthzConfig,
      bindHost: String,
      restPort: Int
  ): Either[String, String] =
    val url = nodeAuthz.replicaUrl(bindHost, restPort)
    if RuntimeType.isFleet(runtimeType) && nodeAuthz.internalUrl.trim.isEmpty then
      Left(InternalUrlFleetMessage)
    else
      nodeUrlHost(url) match
        case None =>
          Left(
            if nodeAuthz.internalUrl.trim.nonEmpty then InternalUrlFormatMessage
            else if nodeAuthz.podIp.trim.nonEmpty then InternalUrlPodIpFormatMessage
            else InternalUrlBindHostFormatMessage
          )
        case Some(host) if RuntimeType.isKubernetes(runtimeType) && unreachableHost(host) =>
          Left(internalUrlKubernetesMessage(url))
        case Some(_) => Right(url)

  private val HttpScheme = "http://"

  /** The host of `url` when qod-node's URL parser (`ParseHttpUrl`, native/qod-node/src/
    * http_post.cpp) accepts it and the host can name a machine: `http://` in lower case, an
    * authority with no user info, a port of 1 to 5 digits in 1-65535 when one is given, no query or
    * fragment, no space or control character in the path. The host is a bracketed IPv6 literal, or
    * a name or IPv4 address of letters, digits, `.`, `-` and `_` (a Compose service such as
    * `qod_manager` included).
    */
  private[boot] def nodeUrlHost(url: String): Option[String] =
    Option.when(url.startsWith(HttpScheme))(url.substring(HttpScheme.length)).flatMap { rest =>
      val (authority, path) = rest.span(_ != '/')
      val pathOk            = path.forall(c => c > ' ' && c != '\u007f' && c != '?' && c != '#')
      if authority.isEmpty || authority.contains('@') || !pathOk then None
      else
        splitAuthority(authority)
          .filter((host, port) => validHost(host) && port.forall(validPort))
          .map(_._1)
    }

  /** host (brackets kept for IPv6) and the port text after its colon, as qod-node splits them. */
  private def splitAuthority(authority: String): Option[(String, Option[String])] =
    if authority.startsWith("[") then
      val close = authority.indexOf(']')
      if close < 0 then None
      else
        val after = authority.substring(close + 1)
        if after.isEmpty then Some((authority.substring(0, close + 1), None))
        else if after.head == ':' then Some((authority.substring(0, close + 1), Some(after.tail)))
        else None
    else
      val colon = authority.lastIndexOf(':')
      if colon < 0 then Some((authority, None))
      else Some((authority.substring(0, colon), Some(authority.substring(colon + 1))))

  private def asciiDigit(c: Char): Boolean = c >= '0' && c <= '9'

  /** qod-node's rule: digits only, at most 5 of them, 1-65535 (`08080` is 8080, `020900` too long).
    */
  private def validPort(port: String): Boolean =
    port.nonEmpty && port.length <= 5 && port.forall(asciiDigit) &&
      port.toInt >= 1 && port.toInt <= 65535

  private def validHost(host: String): Boolean =
    if host.startsWith("[") then ipv6Literal(host).isDefined
    else
      host.nonEmpty && host.forall(c =>
        (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || asciiDigit(c) || c == '.' || c == '-' ||
          c == '_'
      )

  /** A bracketed IPv6 literal's address. `InetAddress` takes a bracketed string as a literal only,
    * so it never looks a name up: anything else throws.
    */
  private def ipv6Literal(bracketed: String): Option[java.net.InetAddress] =
    if !bracketed.contains(':') then None
    else
      try Some(java.net.InetAddress.getByName(bracketed))
      catch case _: java.net.UnknownHostException => None

  /** A host no other machine reaches: a `localhost` name, or a loopback or unspecified address in
    * any form qod-node's resolver (getaddrinfo, with inet_aton's IPv4 forms) reads as one:
    * `127.0.0.1`, `127.1`, `2130706433`, `0x7f.1`, `0177.0.0.1`, `0`, `[::1]`, `[::]`.
    */
  private def unreachableHost(host: String): Boolean =
    if host.startsWith("[") then
      ipv6Literal(host).forall(a => a.isLoopbackAddress || a.isAnyLocalAddress)
    else
      val h = host.stripSuffix(".").toLowerCase(Locale.ROOT)
      h == "localhost" || h.endsWith(".localhost") ||
      inetAton(h).exists(a => (a >>> 24) == 127L || a == 0L)

  /** inet_aton's reading of an IPv4 address: one to four parts, each decimal, octal (leading `0`)
    * or hex (`0x`); the last part fills the bits the others leave. None when `host` is not one.
    */
  private[boot] def inetAton(host: String): Option[Long] =
    val parts = host.split("\\.", -1).toList
    if parts.length > 4 then None
    else
      val numbers = parts.map(atonPart)
      if numbers.exists(_.isEmpty) then None
      else
        val ns       = numbers.flatten
        val lastBits = 8 * (5 - ns.length)
        if ns.init.exists(_ > 255) || ns.last >= (1L << lastBits) then None
        else Some((ns.init.foldLeft(0L)((acc, n) => (acc << 8) | n) << lastBits) | ns.last)

  private def atonPart(part: String): Option[Long] =
    val lower           = part.toLowerCase(Locale.ROOT)
    val (digits, radix) =
      if lower.startsWith("0x") then (lower.drop(2), 16)
      else if lower.length > 1 && lower.startsWith("0") then (lower.drop(1), 8)
      else (lower, 10)
    val legal = (c: Char) => Character.digit(c, radix) >= 0 && c.isLetterOrDigit && c < 128
    if part.isEmpty || !digits.forall(legal) then None
    else
      val value = if digits.isEmpty then BigInt(0) else BigInt(digits, radix)
      Option.when(value <= BigInt(0xffffffffL))(value.toLong)

  /** Bootstrap admin users at startup so the DB auth backend has at least one credential. Seeds
    * each name in QOD_ADMIN_USERNAME (comma-separated) ONLY when its superuser row is missing: an
    * existing row is never touched (password, kind, email, lockout), so `qod auth change-password`
    * survives restarts. Recovery of a lost password is `qod admin reset-password`, not a restart.
    * Superuser scope: tenant=NULL. Returns the warnings it logged (one per existing row when
    * QOD_ADMIN_PASSWORD is explicitly set, since that value is then ignored). An empty
    * `admin.password` never seeds a missing row; it logs an ERROR instead.
    */
  def seedAdminUsers(
      userStore: UserStore,
      admin: AdminConfig,
      env: String => Option[String] = sys.env.get
  ): List[String] =
    val admins = admin.usernameList
    if admins.isEmpty then
      logger.warn("quack-on-demand.admin.username is empty - no admin user seeded.")
      Nil
    else
      val passwordFromEnv = env("QOD_ADMIN_PASSWORD").exists(_.nonEmpty)
      admins.flatMap { name =>
        if admin.password.isEmpty then
          if userStore.userIdOf(None, name).isDefined then None
          else
            val msg =
              s"admin '$name' not seeded: QOD_ADMIN_PASSWORD is not set; export it and restart " +
                "once to create the admin"
            logger.error(msg)
            Some(msg)
        else
          // Same rule as EmailPolicy / the 0031 backfill: an email-format admin username
          // IS its own email, so the seeded admin is reachable for reset and (when lockout
          // is enabled) lockable, consistently on fresh and upgraded installs.
          val seedEmail = if EmailFormat.matches(name) then Some(name) else None
          val out       = userStore.upsertUser(
            tenant = None,
            username = name,
            plaintext = admin.password,
            kind = admin.kind,
            email = Some(seedEmail),
            insertOnly = true
          )
          if out.inserted then
            logger.info(s"admin user created: $name (id=${out.id}, kind=${admin.kind})")
            None
          else if passwordFromEnv then
            val msg =
              s"admin '$name' already exists; QOD_ADMIN_PASSWORD is ignored (use qod auth " +
                "change-password or qod admin reset-password)"
            logger.warn(msg)
            Some(msg)
          else None
      }

  /** Federated sources whose alias [[Names]]'s identifier rule would REWRITE (not merely reject) --
    * every federated alias, `sql` as well as `iceberg_rest`, has been normalized through this rule
    * since the typed iceberg_rest source type landed. This is broader than "invalid": an alias that
    * is a valid identifier but not already lowercase (e.g. `extS3`) is silently rewritten to
    * `exts3` on its next upsert rather than rejected, which is exactly the class `Names.isValid`
    * alone is blind to (its pattern accepts uppercase). Returned as (tenantDbId, alias) pairs.
    *
    * Pure and total: a store failure yields `Nil` rather than throwing, because a legacy alias must
    * never prevent boot. Kept separate from [[checkFederatedAliases]] so the decision (what counts
    * as invalid) is testable directly, without a logging harness.
    *
    * The lowercase test folds through [[FederatedAlias]] rather than `toLowerCase` because it is
    * predicting what `Names.normalizeOrError` (which folds with `Locale.ROOT`) will store on the
    * next upsert. A default-locale fold here would report a `tr`/`az` JVM's single-`I` alias as
    * already-normalized when it is not, and the message below would name a rewrite target the write
    * path never produces.
    */
  private[boot] def invalidFederatedAliases(fedStore: FederatedSourceOps): List[(String, String)] =
    try
      for
        tenantDbId <- fedStore.tenantDbIdsWithSources().toList
        source     <- fedStore.listSources(tenantDbId)
        if !Names.isValid(source.alias) || source.alias != FederatedAlias.fold(source.alias)
      yield (tenantDbId, source.alias)
    catch
      case e: Exception =>
        logger.error(s"checkFederatedAliases: scan failed, skipping: ${e.getMessage}")
        Nil

  /** Boot-time check reporting [[invalidFederatedAliases]]: an alias created before every federated
    * alias was normalized falls into one of two cases.
    *
    *   - **Invalid** (e.g. containing a hyphen or a dot, or over-length): cannot be re-upserted
    *     through REST, CLI, MCP or manifest import until it is recreated under a valid alias, and
    *     if its setup SQL used the bare `{{alias}}` substitution form (rather than a hand-quoted
    *     `AS "{{alias}}"`), its `ATTACH ... AS <alias>` has likely been failing silently at every
    *     node spawn already: DuckDB's parser rejects the unquoted identifier, and the piped CLI
    *     does not bail on that error.
    *   - **Valid but not lowercase** (e.g. `extS3`): will be rewritten in place to its lowercase
    *     form on its next upsert (safe, now that the lookup falls back to a case-insensitive scan
    *     for exactly this case) -- but until then its `ATTACH` may already be colliding with a
    *     sibling alias differing only by case, since DuckDB treats catalog names
    *     case-insensitively.
    *
    * MUST NOT fail boot and MUST NOT throw: an existing install carrying a legacy alias has to keep
    * starting -- `invalidFederatedAliases` already degrades a store failure to `Nil`. Reports
    * nothing when every alias is already normalized, so the overwhelmingly common install stays
    * silent.
    */
  def checkFederatedAliases(fedStore: FederatedSourceOps): Unit =
    invalidFederatedAliases(fedStore).foreach { case (tenantDbId, alias) =>
      // ERROR, not WARN: the default logback root level is ERROR (see logback.xml), so a WARN
      // here would be silently swallowed on every install that hasn't raised the level -- and an
      // operator restarting into this state needs to see it, not lose it.
      val detail =
        if !Names.isValid(alias) then
          "is no longer a valid identifier (lowercase letters, digits and underscore only, not " +
            s"starting with a digit, 1..${Names.MaxLength} chars). It cannot be updated through " +
            "REST, CLI, MCP or manifest import until it is recreated under a valid alias; if its " +
            "setup SQL uses the bare {{alias}} substitution form, its ATTACH has likely been " +
            "failing silently at every node spawn already."
        else
          s"is not lowercase (stored as '$alias'). It will be rewritten to " +
            s"'${FederatedAlias.fold(alias)}' in place the next time it is updated; until then " +
            "its ATTACH may already be colliding with a sibling alias differing only by case, " +
            "since DuckDB treats catalog names case-insensitively."
      logger.error(s"tenant-db '$tenantDbId': federated source alias '$alias' $detail")
    }
