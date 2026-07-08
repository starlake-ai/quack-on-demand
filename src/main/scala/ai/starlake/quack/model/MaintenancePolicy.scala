package ai.starlake.quack.model

/** Per-scope maintenance overrides (EPIC Spec 09). A row is optional -- absent overrides fall back
  * to [[EffectivePolicy.defaults]]. `scopeKind` selects how narrow the row applies: `"tenantdb"`
  * (whole lake), `"schema"` (scopeSchema set), or `"table"` (scopeSchema + scopeTable set). The
  * unique index on (tenant, tenantDb, scopeKind, scopeSchema, scopeTable) (Liquibase 0021)
  * guarantees at most one row per scope tuple.
  */
final case class MaintenancePolicy(
    id: String, // "mpol-..." surrogate
    tenant: String,
    tenantDb: String,
    scopeKind: String, // "tenantdb" | "schema" | "table"
    scopeSchema: Option[String],
    scopeTable: Option[String],
    enabled: Option[Boolean],
    retentionDays: Option[Int],
    compactionEnabled: Option[Boolean],
    targetFileSize: Option[String], // "auto" or e.g. "128MB"
    smallFileMinCount: Option[Int],
    rewriteDeleteThreshold: Option[Double],
    cleanupGraceDays: Option[Int],
    orphanMinAgeDays: Option[Int],
    cron: Option[String],
    updatedAt: Option[java.time.Instant] = None
)

/** Fully-resolved maintenance settings for one scope: the built-in defaults with any
  * [[MaintenancePolicy]] overrides folded in.
  */
final case class EffectivePolicy(
    enabled: Boolean,
    retentionDays: Int,
    compactionEnabled: Boolean,
    targetFileSize: String,
    smallFileMinCount: Int,
    rewriteDeleteThreshold: Double,
    cleanupGraceDays: Int,
    orphanMinAgeDays: Int,
    cron: String
)

object EffectivePolicy:

  /** Built-in defaults (spec section 3). enabled=false: maintenance is opt-in per lake. */
  val defaults: EffectivePolicy = EffectivePolicy(
    enabled = false,
    retentionDays = 7,
    compactionEnabled = true,
    targetFileSize = "auto",
    smallFileMinCount = 12,
    rewriteDeleteThreshold = 0.2,
    cleanupGraceDays = 1,
    orphanMinAgeDays = 1,
    cron = "0 3 * * *"
  )

/** Mutable counters accumulated over one maintenance run. Every field defaults to zero so a partial
  * or freshly-queued run can be represented without `Option` noise.
  */
final case class RunCounters(
    snapshotsExpired: Int = 0,
    snapshotsSkippedPinned: Int = 0,
    filesMerged: Int = 0,
    filesRewritten: Int = 0,
    filesCleaned: Int = 0,
    orphansDeleted: Int = 0,
    bytesReclaimed: Long = 0L
)

/** One row of `qodstate_maintenance_run`: a queued/running/finished execution of the maintenance
  * chain against one scope. `id` is a bigserial so callers can keyset-paginate
  * `listMaintenanceRuns` by descending id.
  */
final case class MaintenanceRun(
    id: Long, // bigserial, keyset cursor
    tenant: String,
    tenantDb: String,
    scope: String,              // "tenantdb" | "table:<schema>.<table>"
    trigger: String,            // "cadence" | "threshold" | "manual"
    operations: Option[String], // csv subset for manual runs; None = full chain
    status: String,             // "queued" | "running" | "succeeded" | "failed" | "partial"
    queuedAt: java.time.Instant,
    startedAt: Option[java.time.Instant],
    finishedAt: Option[java.time.Instant],
    heartbeatAt: Option[java.time.Instant],
    nodeId: Option[String],
    counters: RunCounters,
    error: Option[String]
)
