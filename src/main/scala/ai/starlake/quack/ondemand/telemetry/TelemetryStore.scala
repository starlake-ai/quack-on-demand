package ai.starlake.quack.ondemand.telemetry

import java.time.Instant

/** One audit event. Construction rejects detail keys that look like secrets so passwords, tokens,
  * and secret values can never be persisted by accident (audit-log spec section 5).
  */
final case class AuditEvent(
    ts: Instant,
    family: String,     // "control-plane" | "auth" | "data-denial" | "data-write"
    actor: String,      // username; "anonymous" / "static-key" when the caller is unresolved
    actorRealm: String, // "system" | "tenant"
    tenant: Option[String],
    action: String, // dotted verb, e.g. "role.create", "auth.login.failure", "sql.denied"
    target: Option[String],
    outcome: String, // "ok" | "denied" | "error"
    origin: String,  // "rest" | "flightsql"
    detail: Map[String, String]
):
  require(
    !detail.keys.exists(AuditEvent.forbiddenKey),
    s"audit detail contains a forbidden key: ${detail.keys.filter(AuditEvent.forbiddenKey).mkString(", ")}"
  )

object AuditEvent:
  private val Forbidden                  = List("password", "secret", "token", "jwt", "credential")
  def forbiddenKey(key: String): Boolean =
    val k = key.toLowerCase
    Forbidden.exists(k.contains)

/** Filterable audit read. `tenants = None` means no tenant restriction (superuser);
  * `includeNullTenant = false` hides tenant-less rows from tenant admins.
  */
final case class AuditQuery(
    family: Option[String] = None,
    tenants: Option[Set[String]] = None,
    includeNullTenant: Boolean = true,
    actor: Option[String] = None,
    action: Option[String] = None,
    q: Option[String] = None,
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    limit: Int = 50,
    beforeId: Option[Long] = None
)

final case class AuditRow(id: Long, event: AuditEvent)

/** Storage for telemetry-class data (audit events now; statement history and rollups are added by
  * the history-and-trends plan). Telemetry is append-heavy and time-partitioned and may move to a
  * different database class later, so: no foreign keys into qodstate_* control-plane tables,
  * identities stored as denormalized text, and no implementation detail (SQL, cursors) leaks
  * through this trait.
  */
trait TelemetryStore:
  /** False only for NoopTelemetryStore; drives UI page visibility and fiber wiring. */
  def enabled: Boolean
  def appendAudit(events: List[AuditEvent]): Unit

  /** Newest-first, at most `q.limit` rows, rows with id >= beforeId excluded. */
  def listAudit(q: AuditQuery): List[AuditRow]

  /** Delete events with ts < olderThan; returns rows deleted. */
  def purgeAudit(olderThan: Instant): Int
  def close(): Unit = ()

/** telemetry.store = none: records nothing anywhere, reads return empty. */
object NoopTelemetryStore extends TelemetryStore:
  val enabled                                     = false
  def appendAudit(events: List[AuditEvent]): Unit = ()
  def listAudit(q: AuditQuery): List[AuditRow]    = Nil
  def purgeAudit(olderThan: Instant): Int         = 0
