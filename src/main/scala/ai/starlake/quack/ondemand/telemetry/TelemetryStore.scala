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

// ---------------------------------------------------------------------------
// Statement history models
// ---------------------------------------------------------------------------

/** One executed-statement event captured by the FlightSQL router after execution. The caller is
  * expected to cap `sql` and `error` at 500 characters before constructing.
  */
final case class StatementEvent(
    ts: Instant,
    username: String,
    tenant: String,
    pool: String,
    nodeId: String,
    sql: String, // caller caps at 500 chars before offer
    durationMs: Long,
    prepareMs: Option[Long],
    status: String,       // same enum as the ring: "ok" | "denied" | "error" | ...
    error: Option[String] // caller caps at 500 chars before offer
)

/** Keyset-pageable filter for [[TelemetryStore.searchStatements]].
  *
  * `tenants = None` means no tenant restriction (superuser context). `tenants = Some(emptySet)`
  * means no rows (used by tenant-scoped callers with no tenant). `limit` is clamped to [1, 500] by
  * the store.
  */
final case class StatementQuery(
    tenants: Option[Set[String]] = None,
    pool: Option[String] = None,
    user: Option[String] = None,
    status: Option[String] = None,
    q: Option[String] = None, // substring match on sql
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    limit: Int = 50,
    beforeId: Option[Long] = None
)

/** One row returned by [[TelemetryStore.searchStatements]]. */
final case class StatementRow(id: Long, event: StatementEvent)

// ---------------------------------------------------------------------------
// Rollup models
// ---------------------------------------------------------------------------

/** One aggregated bucket produced by [[TelemetryStore.recomputeRollups]].
  *
  * Hourly buckets set `username = ""` and provide percentile columns. Daily buckets carry a
  * per-user `username` and leave percentile columns as `None`.
  */
final case class RollupBucket(
    bucketStart: Instant,
    granularity: String, // "hour" | "day"
    tenant: String,
    pool: String,
    username: String, // "" on hourly rows
    stmtCount: Long,
    errorCount: Long, // statuses not in (ok, denied)
    deniedCount: Long,
    engineMsSum: Long,
    p50Ms: Option[Double], // hourly only
    p95Ms: Option[Double],
    p99Ms: Option[Double]
)

/** Filter for [[TelemetryStore.queryRollups]].
  *
  * `tenants = None` means no tenant restriction (superuser context). `from`/`to` bound
  * `bucketStart` (inclusive lower, exclusive upper).
  */
final case class RollupQuery(
    granularity: String, // "hour" | "day"
    tenants: Option[Set[String]] = None,
    pool: Option[String] = None,
    from: Option[Instant] = None,
    to: Option[Instant] = None
)

// ---------------------------------------------------------------------------
// Trait and Noop store
// ---------------------------------------------------------------------------

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

  // --- Statement history -------------------------------------------------- //

  /** Append a batch of statement events. Implementations should be fire-and-forget safe; callers do
    * not check the return value.
    */
  def appendStatements(events: List[StatementEvent]): Unit

  /** Return statement rows matching `q`, newest-first (descending id), limit clamped to [1,500].
    */
  def searchStatements(q: StatementQuery): List[StatementRow]

  /** Delete statement rows with ts < olderThan; returns the number of rows deleted. */
  def purgeStatements(olderThan: Instant): Int

  // --- Rollups ------------------------------------------------------------ //

  /** Return the timestamp of the last completed rollup pass, or None if rollups have never run. */
  def rollupWatermark(): Option[Instant]

  /** Recompute hourly and daily rollup buckets for the time range (fromExclusive, toInclusive].
    * Replaces any existing buckets whose bucketStart falls within that window.
    */
  def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit

  /** Advance the rollup watermark to `to` (persisted so the next incremental pass knows where to
    * start).
    */
  def advanceRollupWatermark(to: Instant): Unit

  /** Return rollup buckets matching `q`, ascending by bucketStart. */
  def queryRollups(q: RollupQuery): List[RollupBucket]

  /** Delete rollup buckets with bucketStart < olderThan for the given granularity; returns the
    * number of rows deleted.
    */
  def purgeRollups(granularity: String, olderThan: Instant): Int

/** telemetry.store = none: records nothing anywhere, reads return empty. */
object NoopTelemetryStore extends TelemetryStore:
  val enabled                                                                               = false
  def appendAudit(events: List[AuditEvent]): Unit                                           = ()
  def listAudit(q: AuditQuery): List[AuditRow]                                              = Nil
  def purgeAudit(olderThan: Instant): Int                                                   = 0
  override def appendStatements(events: List[StatementEvent]): Unit                         = ()
  override def searchStatements(q: StatementQuery): List[StatementRow]                      = Nil
  override def purgeStatements(olderThan: Instant): Int                                     = 0
  override def rollupWatermark(): Option[Instant]                                           = None
  override def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit = ()
  override def advanceRollupWatermark(to: Instant): Unit                                    = ()
  override def queryRollups(q: RollupQuery): List[RollupBucket]                             = Nil
  override def purgeRollups(granularity: String, olderThan: Instant): Int                   = 0
