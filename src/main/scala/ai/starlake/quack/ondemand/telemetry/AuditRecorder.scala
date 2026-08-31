package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.api.SessionTokenStore
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal

/** Synchronous best-effort audit writer for the low-volume control-plane and auth event families
  * (audit-log spec section 3). A failed write logs, counts, and never fails the admin operation,
  * which has already happened.
  */
final class AuditRecorder(
    store: TelemetryStore,
    /** Resolves a bearer to the session it authenticates. The composed lookup wired at boot
      * (`Main.scala`) tries a session JWT first, then falls back to a PAT bearer resolved through
      * `PatAuthenticator.sessionOf`, so a PAT-authenticated REST/MCP call attributes to its real
      * owner instead of falling through to the "unresolved token" `static-key` branch below.
      */
    sessionLookup: String => Option[SessionTokenStore.Session],
    /** Resolves a bearer to the `qodstate_pat` id it presented, when it is a PAT. `None` for a
      * session token, the static key, or no token at all -- the default no-op keeps every
      * pre-existing construction site (tests, `AuditRecorder.noop`) behaving exactly as before.
      */
    patIdOf: String => Option[String] = _ => None,
    onDrop: Int => Unit = _ => ()
) extends LazyLogging:

  @volatile private var dropHook: Int => Unit = onDrop

  /** Wire a drop counter after construction (called from runWithMetrics once metricsReg is live).
    */
  def onDropCounter(f: Int => Unit): Unit = dropHook = f

  /** (actor, realm) for a REST caller: session token wins; an unresolved non-empty token is the
    * static QOD_API_KEY; no token at all is the open dev mode.
    */
  def actorOf(token: Option[String]): (String, String) =
    token.flatMap(sessionLookup) match
      case Some(s) => (s.profile.username, if s.scope.superuser then "system" else "tenant")
      case None    => (if token.exists(_.nonEmpty) then "static-key" else "anonymous", "system")

  def rest(
      token: Option[String],
      family: String,
      action: String,
      outcome: String,
      tenant: Option[String] = None,
      target: Option[String] = None,
      detail: Map[String, String] = Map.empty,
      /** Explicit override for the acting token id, for a future caller that already knows its own
        * principal's token id some other way. Falls back to `token.flatMap(patIdOf)` when omitted,
        * which is what fills `pat_id` for the many `audit.rest(apiKey, ...)` call sites that never
        * pass this argument at all -- a PAT bearer curried in as `apiKey` (MCP admin tools calling
        * through the REST handlers) resolves here with zero call-site changes.
        */
      patId: Option[String] = None
  ): Unit =
    val (actor, realm) = actorOf(token)
    val effectivePatId = patId.orElse(token.flatMap(patIdOf))
    restAs(actor, realm, family, action, outcome, tenant, target, detail, effectivePatId)

  def restAs(
      actor: String,
      actorRealm: String,
      family: String,
      action: String,
      outcome: String,
      tenant: Option[String] = None,
      target: Option[String] = None,
      detail: Map[String, String] = Map.empty,
      /** The `qodstate_pat` id of the token that acted. `rest` resolves this automatically from the
        * bearer via `patIdOf` before delegating here; a caller that already has an actor/realm
        * resolved some other way (bypassing `rest`) passes it explicitly, and `None` is the correct
        * value for session and static-key callers.
        */
      patId: Option[String] = None
  ): Unit =
    if store.enabled then
      try
        store.appendAudit(
          List(
            AuditEvent(
              Instant.now(),
              family,
              actor,
              actorRealm,
              tenant,
              action,
              target,
              outcome,
              "rest",
              detail,
              patId
            )
          )
        )
      catch
        case NonFatal(e) =>
          dropHook(1)
          logger.error(s"audit write failed for action '$action': ${e.getMessage}")

object AuditRecorder:
  val noop: AuditRecorder = new AuditRecorder(NoopTelemetryStore, _ => None)

/** One event per key per interval; used to keep anonymous /api 401 bursts (port scans) from
  * flooding the audit table.
  */
final class AuditRateLimiter(
    intervalMillis: Long = 60000,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val last                = new ConcurrentHashMap[String, java.lang.Long]()
  def allow(key: String): Boolean =
    val now  = clock()
    val prev = last.get(key)
    if prev == null || now - prev >= intervalMillis then
      last.put(key, now)
      true
    else false
