package ai.starlake.quack

import ai.starlake.quack.config.ConfigField
import ai.starlake.quack.ondemand.fleet.Cidr

import scala.annotation.meta.field

final case class K8sConfig(
    @field @ConfigField(
      envVar = "QOD_K8S_NAMESPACE",
      description = "Kubernetes namespace KubernetesQuackBackend operates in."
    )
    namespace: String,
    @field @ConfigField(
      envVar = "QOD_K8S_IMAGE",
      description = "Docker image used for spawned Quack-node pods."
    )
    image: String,
    @field @ConfigField(
      envVar = "QOD_K8S_SERVICE_ACCOUNT",
      description = "ServiceAccount applied to spawned node pods (unset = default)."
    )
    serviceAccount: Option[String],
    @field @ConfigField(
      envVar = "QOD_K8S_SERVICE_TYPE",
      description = "Kubernetes Service type fronting node pods."
    )
    serviceType: String,
    @field @ConfigField(
      envVar = "QOD_K8S_QUACK_PORT",
      description = "Container port exposing each node's /quack endpoint."
    )
    quackPort: Int,
    @field @ConfigField(
      envVar = "QOD_K8S_STARTUP_TIMEOUT_SEC",
      description = "Seconds to wait for a spawned node pod to become ready."
    )
    startupTimeoutSec: Int,
    @field @ConfigField(
      envVar = "QOD_K8S_STOP_TIMEOUT_SEC",
      description =
        "Seconds stop() waits for a deleted node pod to actually disappear before proceeding."
    )
    stopTimeoutSec: Int = 60,
    @field @ConfigField(
      envVar = "QOD_K8S_POD_LABEL",
      description = "Label selector that identifies manager-owned node pods."
    )
    podLabel: String,
    @field @ConfigField(
      envVar = "QOD_POD_TEMPLATE_ENABLED",
      description =
        "Allow superusers to supply a full Pod-manifest YAML template for a pool's node pods. Off by default; raw manifests are cluster-level power."
    )
    podTemplateEnabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_K8S_RUN_AS_USER",
      description =
        "Pod-level runAsUser/fsGroup applied to spawned node pods. A pod template's own securityContext.runAsUser (if set) wins over this default."
    )
    runAsUser: Long = 1000L
)

final case class AdminConfig(
    // Comma-separated list of admin usernames. All get the same password +
    // kind on seed. Stored as a single string so a single env var can
    // override (HOCON env-var substitution can't inject a list).
    @field @ConfigField(
      envVar = "QOD_ADMIN_USERNAME",
      description = "Comma-separated admin usernames seeded into qodstate_user."
    )
    username: String,
    @field @ConfigField(
      envVar = "QOD_ADMIN_PASSWORD",
      description =
        "Bootstrap admin password, used only when the admin row does not exist yet; empty (the " +
          "default) seeds nothing.",
      sensitive = true
    )
    password: String,
    @field @ConfigField(
      envVar = "QOD_ADMIN_KIND",
      description = "Account kind (qodstate_user.kind) assigned to the bootstrap admin user."
    )
    kind: String
):
  def usernameList: List[String] =
    username.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList

final case class FederationConfig(
    @field @ConfigField(
      envVar = "QOD_FEDERATION_SECRET_STORE",
      description =
        "Federation secret resolver: postgres | env | aws-sm | gcp-sm | azure-kv | vault."
    )
    secretStore: String
)

/** Generic OIDC client for admin-UI SSO (system/superuser scope). Endpoints are resolved from
  * `${issuerUrl}/.well-known/openid-configuration` via OIDC Discovery, so any compliant IdP works.
  */
final case class ManagementOidcConfig(
    @field @ConfigField(
      envVar = "QOD_MGMT_OIDC_ISSUER_URL",
      description = "OIDC issuer URL for admin-UI SSO (system scope), e.g. " +
        "https://accounts.google.com or http://keycloak:8080/auth/realms/qod. Discovery reads " +
        "${issuerUrl}/.well-known/openid-configuration. Empty disables system-scope SSO."
    )
    issuerUrl: String = "",
    @field @ConfigField(
      envVar = "QOD_MGMT_OIDC_CLIENT_ID",
      description = "OIDC client id for admin-UI SSO (system scope)."
    )
    clientId: String = "",
    @field @ConfigField(
      envVar = "QOD_MGMT_OIDC_CLIENT_SECRET",
      description = "OIDC client secret for admin-UI SSO (system scope).",
      sensitive = true
    )
    clientSecret: String = "",
    @field @ConfigField(
      envVar = "QOD_MGMT_OIDC_SCOPES",
      description = "OIDC scopes requested for admin-UI SSO. Default 'openid email profile'."
    )
    scopes: String = "openid email profile"
)

final case class ManagementAuthConfig(
    @field @ConfigField(
      envVar = "QOD_MGMT_IDENTITY_SOURCE",
      description =
        "System-scope (bare /ui/) admin-UI login mode: 'db' (password form) or 'oidc' (SSO). " +
          "Per-tenant login mode is read from the tenant's authProvider, not this key."
    )
    identitySource: String,
    @field @ConfigField(
      envVar = "QOD_SESSION_JWT_SECRET",
      description =
        "HS256 secret used to sign UI session JWTs and password-reset links. Empty (the " +
          "default) = the control plane's: generated once by the first boot, stored in " +
          "qodstate_boot_secret and read by every restart and every HA replica, so sessions " +
          "survive restarts. A set value (>= 32 chars) wins and is never stored; set one when " +
          "another service must verify the sessions (the Starflow pairing).",
      sensitive = true
    )
    sessionJwtSecret: String,
    @field @ConfigField(
      envVar = "QOD_SESSION_COOKIE_SECURE",
      description =
        "Whether the qod_session cookie carries the `Secure` flag. Accepts 'auto' (default, " +
          "derives from the request's X-Forwarded-Proto -- https=Secure, http or absent=not " +
          "Secure), 'true' (force Secure regardless of request scheme; use behind a TLS " +
          "ingress that strips X-Forwarded-Proto), or 'false' (force not Secure)."
    )
    sessionCookieSecure: String,
    @field @ConfigField(
      envVar = "QOD_SESSION_COOKIE_PATH",
      description =
        "Path attribute on the qod_session cookie. Default '/api'. Override when the manager " +
          "sits behind a path-rewriting reverse proxy: the value must match the BROWSER-visible " +
          "URL prefix, not the backend's. E.g. proxy at https://platform/quack/api/* -> " +
          "QOD_SESSION_COOKIE_PATH=/quack/api."
    )
    sessionCookiePath: String,
    @field @ConfigField(
      envVar = "QOD_MGMT_PUBLIC_BASE_URL",
      description = "Externally visible manager base URL (e.g. https://qod.example.com). " +
        "Used to build OIDC redirect_uri and post_logout_redirect_uri for admin-UI SSO. " +
        "When empty, falls back to http://localhost on the manager's REST port " +
        "(http://localhost:20900 by default); set it for any non-localhost deploy."
    )
    publicBaseUrl: String = "",
    oidc: ManagementOidcConfig = ManagementOidcConfig(),
    @field @ConfigField(
      envVar = "SL_ENABLED",
      description =
        "Enable the Starlake SSO integration (menu link, ticket endpoints, logout callback)"
    )
    slEnabled: Boolean = false,
    @field @ConfigField(
      envVar = "SL_URL",
      description = "Starlake base URL for the SSO handoff and logout callback, e.g. " +
        "https://starlake.example.com"
    )
    slUrl: String = ""
):
  /** Effective enablement: `SL_ENABLED` alone is not enough to turn the integration on -- an empty
    * `SL_URL` would mean redirecting the browser nowhere. Everything that gates the SSO surface
    * (endpoint mounting, the client-config flag, the menu link) reads this, never `slEnabled`
    * directly.
    */
  def slIntegrationOn: Boolean = slEnabled && slUrl.trim.nonEmpty

/** Phase 2 account lockout. Off by default so existing deployments boot unchanged. Enabling it
  * without a working SMTP relay would strand a locked-out user with no way back in -- Main's boot
  * gate (`BootPreflight.checkLockoutSmtp`) refuses to start in that combination. Enforcement
  * (counting failures, locking, checking the lock on login) is Task 9; this config only carries the
  * knobs.
  */
final case class LockoutConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_LOCKOUT_ENABLED",
      description =
        "Lock a database-backed user out after maxFailures consecutive bad passwords. Requires " +
          "SMTP to be configured (quack-on-demand.smtp.host) so a locked-out user has a self-service " +
          "reset path; Main refuses to boot otherwise."
    )
    enabled: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_AUTH_LOCKOUT_MAX_FAILURES",
      description = "Consecutive failed logins before a database-backed user is locked out."
    )
    maxFailures: Int = 10
)

final case class ManagerAuthConfig(
    management: ManagementAuthConfig,
    lockout: LockoutConfig = LockoutConfig()
)

/** Typed view of the `quack-on-demand.defaultMetastore` block. Every scalar maps to an env-var
  * override the spawn-quack-node.sh contract passes through to child nodes. `asMap` projects the
  * fields into the `Map[String, String]` shape consumed by backends and state stores.
  */
final case class DefaultMetastoreConfig(
    @field @ConfigField(
      envVar = "QOD_PG_HOST",
      description = "Postgres host for control plane + DuckLake catalog."
    )
    pgHost: String,
    @field @ConfigField(envVar = "QOD_PG_PORT", description = "Postgres port.")
    pgPort: String,
    @field @ConfigField(
      envVar = "QOD_PG_USER",
      description = "Postgres username used by the manager + Quack nodes."
    )
    pgUser: String,
    @field @ConfigField(
      envVar = "QOD_PG_PASSWORD",
      description = "Postgres password.",
      sensitive = true
    )
    pgPassword: String,
    @field @ConfigField(
      envVar = "QOD_PG_DBNAME",
      description = "Control-plane database name (default 'qod')."
    )
    dbName: String,
    @field @ConfigField(
      envVar = "QOD_PG_SCHEMA",
      description = "Postgres schema for control-plane tables."
    )
    schemaName: String,
    @field @ConfigField(
      envVar = "QOD_DUCKLAKE_DATA_PATH",
      description = "Root path for DuckLake parquet data files."
    )
    dataPath: String
):
  def asMap: Map[String, String] = Map(
    "pgHost"     -> pgHost,
    "pgPort"     -> pgPort,
    "pgUser"     -> pgUser,
    "pgPassword" -> pgPassword,
    "dbName"     -> dbName,
    "schemaName" -> schemaName,
    "dataPath"   -> dataPath
  )

final case class HaConfig(
    @field @ConfigField(
      envVar = "QOD_HA_ENABLED",
      description = "Enable active-active multi-replica manager mode (kubernetes or fleet runtime)."
    )
    enabled: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_LEADER_RETRY_SEC",
      description = "Seconds between leader-lock acquisition attempts and LISTEN polls."
    )
    leaderRetrySec: Int = 3,
    @field @ConfigField(
      envVar = "QOD_TOPOLOGY_REFRESH_SEC",
      description = "Seconds between snapshot-refresh fallback passes in HA mode."
    )
    topologyRefreshSec: Int = 30
)

/** Managed-maintenance scheduler + runner (EPIC Spec 09). */
final case class MaintenanceConfig(
    @field @ConfigField(
      envVar = "QOD_MAINT_ENABLED",
      description = "Enable the maintenance scheduler + drain-loop fibers."
    )
    enabled: Boolean = true,
    @field @ConfigField(
      envVar = "QOD_MAINT_TICK_SEC",
      description = "Seconds between maintenance scheduler ticks (cadence + threshold checks)."
    )
    tickSec: Int = 60,
    @field @ConfigField(
      envVar = "QOD_MAINT_MAX_CONCURRENT",
      description = "Max maintenance runs executing concurrently across the manager."
    )
    maxConcurrent: Int = 2,
    @field @ConfigField(
      envVar = "QOD_MAINT_MIN_INTERVAL_MIN",
      description = "Minimum minutes between non-manual maintenance runs of the same tenant-db."
    )
    minIntervalMin: Int = 30,
    @field @ConfigField(
      envVar = "QOD_MAINT_RUN_TIMEOUT_MIN",
      description =
        "Minutes without a heartbeat before a running maintenance run is swept as failed."
    )
    runTimeoutMin: Int = 60,
    @field @ConfigField(
      envVar = "QOD_MAINT_NODE_READY_TIMEOUT_SEC",
      description =
        "Seconds to wait for the ephemeral maintenance node to accept connections after spawn " +
          "before the run is failed as 'node spawn failed'. Covers cold-start extension installs."
    )
    nodeReadyTimeoutSec: Int = 180
)

// runTimeoutMin must exceed the longest single chain step (flush/expire/merge/rewrite/cleanup/
// orphans): the sweep only checks time-since-last-heartbeat, and a heartbeat is only written
// between steps, not during one. A step that legitimately runs longer than runTimeoutMin gets
// swept out from under a still-healthy run, racing MaintenanceRunner's own finishMaintenanceRun
// call (see the "AND status = 'running'" guard on both UPDATEs in PostgresControlPlaneStore).

/** Catalog browser read surface (Spec 00 time-travel viewer). */
final case class CatalogConfig(
    @field @ConfigField(
      envVar = "QOD_AUDIT_CATALOG_READS",
      description =
        "Audit catalog browser reads: one catalog.read event per gated GET. Off by default " +
          "(reads are chatty; mutations are always audited)."
    )
    auditCatalogReads: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_CATALOG_PREVIEW_MAX_ROWS",
      description = "Hard cap on rows returned by the catalog data-preview endpoint."
    )
    previewMaxRows: Int = 1000,
    @field @ConfigField(
      envVar = "QOD_CATALOG_PREVIEW_TIMEOUT_SEC",
      description = "Seconds before a catalog data-preview query is cancelled."
    )
    previewTimeoutSec: Int = 30,
    @field @ConfigField(
      envVar = "QOD_CATALOG_ICEBERG_DIFF_MAX_FILES",
      description =
        "Refuse an Iceberg data diff when either snapshot has more data files than this: the " +
          "diff is a full EXCEPT scan of both versions."
    )
    icebergDiffMaxFiles: Int = 200,
    @field @ConfigField(
      envVar = "QOD_CATALOG_UNDROP_TIMEOUT_SEC",
      description =
        "Seconds before an undrop recovery CTAS is abandoned. Larger than the preview timeout " +
          "because it is a mutation over potentially large tables; on timeout the handler " +
          "probes whether the table was created anyway and reports accordingly."
    )
    undropTimeoutSec: Int = 300,
    @field @ConfigField(
      envVar = "QOD_CATALOG_RESTORE_TIMEOUT_SEC",
      description =
        "Seconds before a restore CREATE OR REPLACE is abandoned. On timeout the handler probes " +
          "whether the replace committed anyway and reports accordingly."
    )
    restoreTimeoutSec: Int = 300
)

/** Personal access tokens (`PatHandlers`, `PatStore`).
  *
  * `maxDepth` is an OPERATIONAL backstop on the delegation chain (bounded revocation cascade,
  * bounded row growth in `qodstate_pat`, a delegation graph a human can read during an incident),
  * NOT a security boundary: `TokenRestriction.narrow` already guarantees a child can never exceed
  * its parent on any axis, so an unbounded chain could not escalate privilege even without this
  * cap. Do not reason about `maxDepth` as a defence against privilege escalation.
  */
final case class PatConfig(
    @field @ConfigField(
      envVar = "QOD_PAT_MAX_DEPTH",
      description =
        "Max depth of a PAT delegation chain (root = 0). Operational backstop on the revocation " +
          "cascade and row growth, not a security boundary -- narrow() already bounds privilege."
    )
    maxDepth: Int = 8
)

/** MCP server for AI agents at POST /mcp. Auth: a personal access token as Bearer; session JWTs and
  * passwords are never accepted there.
  */
final case class McpConfig(
    @field @ConfigField(
      envVar = "QOD_MCP_ENABLED",
      description = "Serve the MCP endpoint at POST /mcp."
    )
    enabled: Boolean = true,
    @field @ConfigField(
      envVar = "QOD_MCP_MAX_ROWS",
      description = "Hard cap on rows returned by the run_sql MCP tool."
    )
    maxRows: Int = 500
)

final case class TelemetryConfig(
    @field
    @ConfigField(
      envVar = "QOD_TELEMETRY_STORE",
      description =
        "Telemetry store backing audit log (and, later, history/usage): postgres | none (record nothing)."
    )
    store: String = "postgres",
    @field
    @ConfigField(
      envVar = "QOD_AUDIT_RETENTION_DAYS",
      description = "Days to keep audit events before the hourly purge deletes them."
    )
    auditRetentionDays: Int = 90,
    @field
    @ConfigField(
      envVar = "QOD_TELEMETRY_JOURNAL_CAPACITY",
      description =
        "Bounded in-process telemetry journal capacity; overflow drops events (counted)."
    )
    journalCapacity: Int = 8192,
    @field
    @ConfigField(
      envVar = "QOD_STMT_HISTORY_RETENTION_DAYS",
      description = "Days to keep statement-history rows before the periodic purge removes them."
    )
    stmtHistoryRetentionDays: Int = 7,
    @field
    @ConfigField(
      envVar = "QOD_HOURLY_ROLLUP_RETENTION_DAYS",
      description = "Days to keep hourly rollup buckets before the periodic purge removes them."
    )
    hourlyRollupRetentionDays: Int = 90,
    @field
    @ConfigField(
      envVar = "QOD_ROLLUP_INTERVAL_SEC",
      description =
        "Seconds between rollup computation passes that aggregate raw statement history into rollup buckets."
    )
    rollupIntervalSec: Int = 300,
    @field
    @ConfigField(
      envVar = "QOD_USAGE_RETENTION_DAYS",
      description =
        "Days to keep daily rollup buckets (the usage-accounting ledger) before the periodic purge removes them. 400 covers a full billing year."
    )
    usageRetentionDays: Int = 400,
    @field
    @ConfigField(
      envVar = "QOD_AUDIT_SINK",
      description =
        "Extra destination for every audit and statement event, on top of the store: none | stdout (one JSON line per event, tagged qodEvent=audit|statement, for a log shipper; SQL literals, engine error text and unauthenticated login names are redacted). Requires store=postgres. Lines dropped under stdout backpressure count in qod_journal_dropped_total{table=stdout}."
    )
    auditSink: String = "none"
)

object TelemetryConfig:
  def validate(
      store: String,
      stmtHistoryRetentionDays: Int,
      auditSink: String = "none"
  ): Either[String, Unit] =
    store match
      case "postgres" | "none" =>
        if stmtHistoryRetentionDays != 0 && stmtHistoryRetentionDays < 2 then
          Left(
            "telemetry.stmtHistoryRetentionDays must be 0 (keep forever) or >= 2: the daily" +
              " rollup recompute rebuilds whole-day buckets from raw rows"
          )
        else if auditSink != "none" && auditSink != "stdout" then
          Left(s"unknown telemetry.auditSink: '$auditSink' (supported: none, stdout)")
        else if auditSink != "none" && store == "none" then
          Left("telemetry.auditSink requires telemetry.store=postgres: store=none records nothing")
        else Right(())
      case other => Left(s"unknown telemetry.store: '$other' (supported: postgres, none)")

final case class ManagerConfig(
    @field @ConfigField(
      envVar = "QOD_ON_DEMAND_HOST",
      description = "Manager REST bind address (0.0.0.0 to listen on all interfaces)."
    )
    host: String,
    @field @ConfigField(
      envVar = "QOD_ON_DEMAND_PORT",
      description = "Manager REST + admin UI port."
    )
    port: Int,
    @field @ConfigField(
      envVar = "QOD_RUNTIME_TYPE",
      description =
        "Quack node runtime backend: 'local' (child processes), 'kubernetes' (pods) or 'fleet' " +
          "(one node per server joined through qod fleet join)."
    )
    runtimeType: String,
    @field @ConfigField(
      envVar = "QOD_MIN_PORT",
      description = "Lower bound of the port range LocalQuackBackend allocates child nodes from."
    )
    minPort: Int,
    @field @ConfigField(
      envVar = "QOD_MAX_PORT",
      description = "Upper bound of the port range LocalQuackBackend allocates child nodes from."
    )
    maxPort: Int,
    @field @ConfigField(
      envVar = "QOD_MAX_NODES_TOTAL",
      description = "Hard cap on concurrent child nodes across all pools."
    )
    maxNodesTotal: Int,
    @field @ConfigField(
      envVar = "QOD_STAMP_WRITES",
      description =
        "Stamp DuckLake snapshots created by FlightSQL DML/DDL with author and commit message " +
          "(native wire bracket; fail-open). Off = writes are never bracketed."
    )
    stampWrites: Boolean,
    @field @ConfigField(
      envVar = "QOD_QUACK_NODE_LEASE_SEC",
      description =
        "Lease (seconds, >= 1) the manager asks a node for on each native connection. The manager " +
          "heartbeats at a third of it. The node checks the lease only when the connection's " +
          "next message arrives; once lapsed, that message fails and the node interrupts what " +
          "still runs on the connection and drops it. A lapse by itself interrupts nothing, so " +
          "the lease does not bound work orphaned by a dead manager."
    )
    nodeLeaseSec: Int = 30,
    @field @ConfigField(
      envVar = "QOD_SPAWN_SCRIPT",
      description = "Path to spawn-quack-node.sh invoked by LocalQuackBackend on Unix."
    )
    spawnScript: String,
    @field @ConfigField(
      envVar = "QOD_SPAWN_SCRIPT_WINDOWS",
      description =
        "Path to the PowerShell spawn script (spawn-quack-node.ps1) invoked by LocalQuackBackend " +
          "on Windows."
    )
    spawnScriptWindows: String,
    @field @ConfigField(
      envVar = "QOD_DRAIN_TIMEOUT_SEC",
      description = "Seconds to wait for in-flight statements during graceful pool shutdown."
    )
    drainTimeoutSec: Int,
    @field @ConfigField(
      envVar = "QOD_HEALTH_CHECK_INTERVAL_SEC",
      description = "Seconds between supervisor health checks against child nodes."
    )
    healthCheckIntervalSec: Int,
    @field @ConfigField(
      envVar = "QOD_RECONCILE_INTERVAL_SEC",
      description =
        "Seconds between supervisor reconcile passes that respawn dead nodes. 0 disables the " +
          "periodic loop (reconcile still runs once at boot)."
    )
    reconcileIntervalSec: Int,
    ha: HaConfig = HaConfig(),
    telemetry: TelemetryConfig = TelemetryConfig(),
    maintenance: MaintenanceConfig = MaintenanceConfig(),
    catalog: CatalogConfig = CatalogConfig(),
    routing: RoutingConfig = RoutingConfig(),
    autoscale: AutoscaleConfig = AutoscaleConfig(),
    hibernation: HibernationConfig = HibernationConfig(),
    branching: BranchingConfig = BranchingConfig(),
    fleet: FleetConfig = FleetConfig(),
    managedObjectStore: ManagedObjectStoreConfig = ManagedObjectStoreConfig(),
    smtp: SmtpConfig = SmtpConfig(),
    mcp: McpConfig = McpConfig(),
    pat: PatConfig = PatConfig(),
    tenantFiles: TenantFilesConfig = TenantFilesConfig(),
    userS3: UserS3Config = UserS3Config(),
    nodeAuthz: NodeAuthzConfig = NodeAuthzConfig(),
    nodeExtensions: NodeExtensionsConfig = NodeExtensionsConfig(),
    nodeAuthzLimits: NodeAuthzLimitsConfig = NodeAuthzLimitsConfig(),
    @field @ConfigField(
      envVar = "QOD_PUBLIC_BASE_URL",
      description =
        "Externally visible base URL (e.g. https://qod.example.com) used to build password-reset " +
          "links mailed to users. When empty the link is host-relative (/ui/reset-password?...) " +
          "and Main logs a boot warning."
    )
    publicBaseUrl: String = "",
    @field @ConfigField(
      envVar = "QOD_REQUIRE_ENCRYPTION",
      description =
        "Refuse `database/create` unless the caller asks for encryption at rest. Gates creates " +
          "only: existing databases are untouched, so turning this on never bricks a running " +
          "deployment."
    )
    requireEncryption: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_SESSION_IDLE_TTL_SEC",
      description =
        "UI session idle TTL in seconds. A session unused for this long is dropped on the next " +
          "access; each successful access slides the window. Manager restart still invalidates " +
          "everything (sessions are heap-only)."
    )
    sessionIdleTtlSec: Int,
    embeddedPostgres: EmbeddedPostgresConfig = EmbeddedPostgresConfig(),
    defaultMetastore: DefaultMetastoreConfig,
    admin: AdminConfig,
    k8s: K8sConfig,
    federation: FederationConfig,
    auth: ManagerAuthConfig
):
  require(
    nodeLeaseSec >= 1,
    s"nodeLeaseSec (QOD_QUACK_NODE_LEASE_SEC) must be >= 1, got $nodeLeaseSec"
  )

/** Where tenants may authorize local folders for their users (in-engine authorization, decision
  * 15): only under `<root>/<tenant id>/`. Unset, no local folder can be authorized. The root must
  * be the real path on the nodes (no symlink component): nodes check real paths.
  */
final case class TenantFilesConfig(
    @field @ConfigField(
      envVar = "QOD_TENANT_FILES_ROOT",
      description =
        "Absolute folder under which tenant admins may authorize local folders, one subfolder per " +
          "tenant id (<root>/<tenant>/...). Unset: no local folder can be authorized, and " +
          "non-superusers reach no local file. Must be a real path on the nodes."
    )
    root: String = ""
):
  require(
    root.trim.isEmpty || TenantFilesConfig.validRoot(root.trim),
    s"tenantFiles.root (QOD_TENANT_FILES_ROOT) must be an absolute folder of at least one " +
      s"segment with no empty, '.' or '..' segment, got '$root'"
  )

  /** The root without its trailing `/`, or None when unset. */
  def rootOpt: Option[String] = Option(root.trim).filter(_.nonEmpty).map(_.stripSuffix("/"))

object TenantFilesConfig:
  def validRoot(r: String): Boolean =
    val body = r.stripPrefix("/").stripSuffix("/")
    r.startsWith("/") && body.nonEmpty &&
    body.split("/", -1).forall(s => s.nonEmpty && s != "." && s != "..")

/** The S3-compatible endpoints a user may name for their own S3 credentials (in-engine
  * authorization, decision 23). Empty: AWS's own endpoint only. A node signs a user's requests to
  * the endpoint the user named, so the operator lists the hosts that may be named at all.
  */
final case class UserS3Config(
    @field @ConfigField(
      envVar = "QOD_USER_S3_ALLOWED_ENDPOINTS",
      description =
        "S3-compatible endpoints users may name for their own S3 credentials, comma separated: " +
          "`host[:port]` (TLS) or `http://host[:port]` (plain HTTP). Empty: AWS's own endpoint " +
          "only."
    )
    allowedEndpoints: String = ""
):
  require(
    ai.starlake.quack.model.UserS3CredentialRules.parseAllowedEndpoints(allowedEndpoints).isRight,
    "userS3.allowedEndpoints (QOD_USER_S3_ALLOWED_ENDPOINTS): " +
      ai.starlake.quack.model.UserS3CredentialRules
        .parseAllowedEndpoints(allowedEndpoints)
        .left
        .getOrElse("")
  )

  /** The allowed endpoints, normalized (UserS3CredentialRules.normalizeEndpoint). */
  def endpoints: List[String] =
    ai.starlake.quack.model.UserS3CredentialRules
      .parseAllowedEndpoints(allowedEndpoints)
      .getOrElse(Nil)

/** In-engine authorization: node keys and the URL nodes reach this replica on. */
final case class NodeAuthzConfig(
    @field @ConfigField(
      envVar = "QOD_NODE_KEY_SECRET",
      description =
        "Secret every node key derives from (>= 32 characters). Unset: one value generated by the " +
          "first boot and kept in the control-plane database, shared by every replica. Changing " +
          "it means restarting every node.",
      sensitive = true
    )
    keySecret: String = "",
    @field @ConfigField(
      envVar = "QOD_INTERNAL_URL",
      description =
        "URL nodes reach this replica on, carried in every session handle: nodes call " +
          "it for their statements' verdicts (plain http). Default " +
          "`http://<QOD_POD_IP>:<manager port>` when the pod IP is set, else " +
          "`http://<QOD_ON_DEMAND_HOST>:<manager port>` when the manager listens on one specific " +
          "address, else `http://127.0.0.1:<manager port>`. " +
          "Required in fleet mode (this replica's own address, one per replica, never a load " +
          "balancer); never a loopback URL on Kubernetes."
    )
    internalUrl: String = "",
    @field @ConfigField(
      envVar = "QOD_POD_IP",
      description = "This replica's pod IP; the Helm chart sets it from the downward API. With " +
        "QOD_INTERNAL_URL unset, nodes reach this replica on " +
        "`http://<pod IP>:<manager port>`, so the replica that minted a handle, not the " +
        "Service, answers its node."
    )
    podIp: String = "",
    @field @ConfigField(
      envVar = "QOD_AUTHZ_HANDLE_CONTEXTS_MAX",
      description =
        "How many session handle contexts this replica keeps for its nodes' verdict and " +
          "credential calls (at least 1000). Each is kept 360 s whatever the statement's " +
          "duration, so this caps the replica's sustained tenant statements at about this / 360 " +
          "per second (about 555 at the default); past it a statement can be refused with " +
          "'authorization context expired; retry'. Watch " +
          "cache_evictions_total{cache=\"qod_minted_handles\"}."
    )
    handleContextsMax: Int = 200000
):
  require(
    handleContextsMax >= NodeAuthzConfig.HandleContextsFloor,
    s"nodeAuthz.handleContextsMax (QOD_AUTHZ_HANDLE_CONTEXTS_MAX) must be at least " +
      s"${NodeAuthzConfig.HandleContextsFloor}, got $handleContextsMax"
  )

  /** [[internalUrl]]; else this pod's address on `restPort`; else, when the manager listens on one
    * specific address (`bindHost`, QOD_ON_DEMAND_HOST, neither a wildcard nor `localhost` /
    * `127.0.0.1`), that address; else this host's loopback URL on `restPort`. An IPv6 address goes
    * in brackets.
    */
  def replicaUrl(bindHost: String, restPort: Int): String =
    val pod  = podIp.trim
    val bind = bindHost.trim
    if internalUrl.trim.nonEmpty then internalUrl.trim
    else if pod.nonEmpty then s"http://${urlHost(pod)}:$restPort"
    else if specificBindHost(bind) then s"http://${urlHost(bind)}:$restPort"
    else s"http://127.0.0.1:$restPort"

  private def urlHost(host: String): String =
    if host.contains(':') && !host.startsWith("[") then s"[$host]" else host

  /** A bind address the loopback default does not reach: not blank, not a wildcard, not `localhost`
    * or `127.0.0.1`.
    */
  private def specificBindHost(host: String): Boolean =
    !Set("", "*", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", "localhost", "127.0.0.1")
      .contains(host.toLowerCase(java.util.Locale.ROOT))

object NodeAuthzConfig:

  /** The least [[NodeAuthzConfig.handleContextsMax]] accepted. */
  val HandleContextsFloor: Int = 1000

/** The three extension settings qod-node fixes in a node's database config before it opens (spec
  * decision 17). Off unless turned on here: DuckDB refuses to turn either `allow_*` setting on
  * while a database runs, so this is the only way to turn one on, and a change applies at each
  * node's next start. Every backend hands all three to the spawn script ([[env]]), which passes
  * qod-node a flag for each one that is on.
  */
final case class NodeExtensionsConfig(
    @field @ConfigField(
      envVar = "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS",
      description =
        "Let nodes load community extensions (allow_community_extensions). Off by default; a pool " +
          "or database initSql that installs a community extension needs it. Applies at each " +
          "node's next start."
    )
    allowCommunity: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS",
      description =
        "Let nodes load extensions with a missing or invalid signature (allow_unsigned_extensions). " +
          "Off by default. Applies at each node's next start."
    )
    allowUnsigned: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS",
      description = "Let nodes download a known extension the first time a statement needs it " +
        "(autoinstall_known_extensions). Off by default: nodes install what QoD's own setup " +
        "needs explicitly. Applies at each node's next start."
    )
    autoinstallKnown: Boolean = false
):
  /** The spawn environment carrying these to qod-node: all three names, always, `true` or `false`,
    * so neither a variable the launcher inherited nor a node's metastore decides one.
    */
  def env: Map[String, String] = Map(
    NodeExtensionsConfig.AllowCommunityEnv   -> allowCommunity.toString,
    NodeExtensionsConfig.AllowUnsignedEnv    -> allowUnsigned.toString,
    NodeExtensionsConfig.AutoinstallKnownEnv -> autoinstallKnown.toString
  )

object NodeExtensionsConfig:
  val AllowCommunityEnv: String   = "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS"
  val AllowUnsignedEnv: String    = "QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS"
  val AutoinstallKnownEnv: String = "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS"
  val EnvNames: Set[String]       = Set(AllowCommunityEnv, AllowUnsignedEnv, AutoinstallKnownEnv)

/** The decide limits qod-node reads from its environment at start (in-engine authorization): how
  * long one decide call may take, and how many plan verdicts a node keeps. Every backend hands both
  * to every node it starts ([[env]]), so the manager's setting applies on every runtime (before,
  * Kubernetes pods and fleet nodes ran qod-node's defaults whatever the manager was given); a
  * change applies at each node's next start. The defaults and ranges are qod-node's own
  * (`ParseAuthzLimits`, native/qod-node/src/decide_client.cpp), so a node never refuses to start on
  * what the manager forwards.
  */
final case class NodeAuthzLimitsConfig(
    @field @ConfigField(
      envVar = "QOD_AUTHZ_DECIDE_TIMEOUT_MS",
      description =
        "How long a node waits for one verdict from the manager, connect to last byte, in ms " +
          "(1 to 60000). A slower answer refuses the statement, as retryable when it is a " +
          "single statement. Applies at each node's next start."
    )
    decideTimeoutMs: Int = 2000,
    @field @ConfigField(
      envVar = "QOD_AUTHZ_CACHE_ENTRIES",
      description =
        "How many plan verdicts a node caches (0 to 10000000; 0 caches none). Applies at each " +
          "node's next start."
    )
    cacheEntries: Int = 10000
):
  require(
    decideTimeoutMs >= 1 && decideTimeoutMs <= 60000,
    s"nodeAuthzLimits.decideTimeoutMs (QOD_AUTHZ_DECIDE_TIMEOUT_MS) must be 1 to 60000, " +
      s"got $decideTimeoutMs"
  )
  require(
    cacheEntries >= 0 && cacheEntries <= 10000000,
    s"nodeAuthzLimits.cacheEntries (QOD_AUTHZ_CACHE_ENTRIES) must be 0 to 10000000, " +
      s"got $cacheEntries"
  )

  /** The spawn environment carrying these to qod-node: both names, always. */
  def env: Map[String, String] = Map(
    NodeAuthzLimitsConfig.DecideTimeoutEnv -> decideTimeoutMs.toString,
    NodeAuthzLimitsConfig.CacheEntriesEnv  -> cacheEntries.toString
  )

object NodeAuthzLimitsConfig:
  val DecideTimeoutEnv: String = "QOD_AUTHZ_DECIDE_TIMEOUT_MS"
  val CacheEntriesEnv: String  = "QOD_AUTHZ_CACHE_ENTRIES"
  val EnvNames: Set[String]    = Set(DecideTimeoutEnv, CacheEntriesEnv)

final case class RoutingConfig(
    @field @ConfigField(
      envVar = "QOD_ROUTING_CACHE_AWARE",
      description =
        "Cache-aware placement on object-store pools. false instantly reverts routing decisions " +
          "to pure least-loaded; locality metrics and their memoized statement parse keep running."
    )
    cacheAware: Boolean = true,
    @field @ConfigField(
      envVar = "QOD_ROUTING_LOAD_CAP_FACTOR",
      description =
        "Load cap c: a table's home node is bypassed when its inFlight exceeds c x pool average."
    )
    loadCapFactor: Double = 2.0,
    @field @ConfigField(
      envVar = "QOD_ROUTING_DIRECTORY_MAX_TABLES",
      description = "Per-pool bound on placement-directory entries (LRU-evicted). Safety bound."
    )
    directoryMaxTables: Int = 4096
)

/** Demand scale-out between a pool's owner-declared min/max band. Policy lives in core; per-pool
  * participation requires an explicit band, so the sweep is inert on fresh installs. Two levels of
  * opt-out: `enabled = false` stops the sweep manager-wide, and a pool opts out on its own by
  * declaring `minNodes == maxNodes` (hold that size, never scale) or by carrying no band at all.
  * See docs/superpowers/specs/2026-08-11-demand-scale-out-policy-design.md.
  */
final case class AutoscaleConfig(
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_ENABLED",
      description = "Global kill switch for the demand scale-out sweep."
    )
    enabled: Boolean = true,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_SWEEP_SEC",
      description = "Sweep interval in seconds; clamped to a 30s floor."
    )
    sweepSeconds: Int = 60,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_WINDOW_MINUTES",
      description = "Load window W for the Little's-law concurrency estimate."
    )
    windowMinutes: Int = 5,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_HIGH_WATERMARK",
      description = "Scale-out utilization threshold."
    )
    highWatermark: Double = 0.8,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_LOW_WATERMARK",
      description = "Scale-in utilization threshold; must be < highWatermark."
    )
    lowWatermark: Double = 0.3,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_OUT_STREAK",
      description = "Consecutive sweeps above highWatermark before adding a reader."
    )
    outStreak: Int = 2,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_IN_STREAK",
      description = "Consecutive sweeps below lowWatermark before removing a reader."
    )
    inStreak: Int = 10,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_OUT_COOLDOWN_SEC",
      description = "Per-pool cooldown after any scale action or resume before scaling out."
    )
    scaleOutCooldownSec: Int = 180,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_IN_COOLDOWN_SEC",
      description = "Per-pool cooldown after any scale action before scaling in."
    )
    scaleInCooldownSec: Int = 600,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_ASSUMED_CONCURRENCY",
      description = "Capacity contribution of a node with maxConcurrent = 0 (unlimited)."
    )
    assumedConcurrencyPerNode: Int = 4,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_HARD_CAP",
      description = "Upper bound on maxNodes at validation time; a typo guard, not a quota."
    )
    hardCap: Int = 16,
    @field @ConfigField(
      envVar = "QOD_AUTOSCALE_FAILURE_BACKOFF_SWEEPS",
      description = "Sweeps to skip a pool after 3 consecutive scale failures."
    )
    failureBackoffSweeps: Int = 5
):
  require(lowWatermark < highWatermark, "autoscale: lowWatermark must be < highWatermark")
  require(outStreak >= 1, "autoscale: outStreak must be >= 1")
  require(inStreak >= 1, "autoscale: inStreak must be >= 1")
  require(windowMinutes >= 1, "autoscale: windowMinutes must be >= 1")
  require(scaleOutCooldownSec >= 0, "autoscale: scaleOutCooldownSec must be >= 0")
  require(scaleInCooldownSec >= 0, "autoscale: scaleInCooldownSec must be >= 0")
  require(failureBackoffSweeps >= 1, "autoscale: failureBackoffSweeps must be >= 1")
  require(assumedConcurrencyPerNode >= 0, "autoscale: assumedConcurrencyPerNode must be >= 0")
  require(hardCap >= 1, "autoscale: hardCap must be >= 1")
  def sweepInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(math.max(30, sweepSeconds)).seconds

/** Idle-pool hibernation: the leader suspends a running pool once it has served no statements for
  * its idle window; the FlightSQL edge wakes it on the next statement. Participation is per-pool
  * opt-in via `Pool.idleTimeoutSec` unless `defaultIdleMinutes > 0` sets a manager-wide default
  * (`idleTimeoutSec = 0` still opts a pool out), so the sweep is inert on a fresh install, like
  * autoscale without a band. Policy ported from the hosted module.
  */
final case class HibernationConfig(
    @field @ConfigField(
      envVar = "QOD_HIBERNATE_ENABLED",
      description = "Global kill switch for the idle-pool hibernation sweep."
    )
    enabled: Boolean = true,
    @field @ConfigField(
      envVar = "QOD_HIBERNATE_SWEEP_SEC",
      description = "Sweep interval in seconds; clamped to a 60s floor."
    )
    sweepSeconds: Int = 300,
    @field @ConfigField(
      envVar = "QOD_HIBERNATE_IDLE_MIN",
      description =
        "Manager-wide default idle minutes before a running pool is suspended. 0 (the default) " +
          "means hibernation is per-pool opt-in via idleTimeoutSec. Clamped to a 5-minute floor: " +
          "activity timestamps lag by up to one sweep interval."
    )
    defaultIdleMinutes: Int = 0
):
  require(defaultIdleMinutes >= 0, "hibernation: defaultIdleMinutes must be >= 0")
  def sweepInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(math.max(60, sweepSeconds)).seconds
  def defaultIdle: Option[scala.concurrent.duration.FiniteDuration] =
    Option.when(defaultIdleMinutes > 0)(
      scala.concurrent.duration.DurationInt(math.max(5, defaultIdleMinutes)).minutes
    )

/** Fleet runtime (`runtimeType = fleet`): quack nodes on bare servers that join through
  * `qod fleet join`. See docs/superpowers/specs/2026-09-25-fleet-backend-design.md.
  */
final case class FleetConfig(
    @field @ConfigField(
      envVar = "QOD_FLEET_JOIN_TOKEN",
      description = "Shared secret every fleet heartbeat carries. Required when runtimeType=fleet.",
      sensitive = true
    )
    joinToken: String = "",
    @field @ConfigField(
      envVar = "QOD_FLEET_HEARTBEAT_SEC",
      description =
        "Heartbeat interval in seconds; returned to every qod fleet join process in each reply."
    )
    heartbeatSec: Int = 5,
    @field @ConfigField(
      envVar = "QOD_FLEET_HEARTBEAT_TIMEOUT_SEC",
      description = "Silence beyond this many seconds marks a server unreachable."
    )
    heartbeatTimeoutSec: Int = 30,
    @field @ConfigField(
      envVar = "QOD_FLEET_REASSIGN_AFTER_SEC",
      description =
        "Unreachable beyond this many seconds moves the server's node slot to a free server. " +
          "0 = at once, -1 = never."
    )
    reassignAfterSec: Int = 600,
    @field @ConfigField(
      envVar = "QOD_FLEET_STARTUP_TIMEOUT_SEC",
      description = "How long a claim waits for the server to report the node running."
    )
    startupTimeoutSec: Int = 120,
    @field @ConfigField(
      envVar = "QOD_FLEET_STOP_TIMEOUT_SEC",
      description = "How long a release waits for the server to report the node stopped."
    )
    stopTimeoutSec: Int = 60,
    @field @ConfigField(
      envVar = "QOD_FLEET_EPHEMERAL",
      description =
        "Where maintenance and branch-merge nodes run: 'fleet' claims a server like any node, " +
          "'local' runs them on the manager host through the local backend."
    )
    ephemeral: String = "fleet",
    @field @ConfigField(
      envVar = "QOD_FLEET_AUTO_APPROVE",
      description =
        "Comma-separated CIDRs: a server whose heartbeat comes from one of them is approved on " +
          "join; any other waits for `qod fleet approve`. Empty = approve none automatically. " +
          "The default admits every address."
    )
    autoApprove: String = "0.0.0.0/0,::/0",
    @field @ConfigField(
      envVar = "QOD_FLEET_TRUSTED_PROXIES",
      description =
        "Comma-separated CIDRs of proxies / load balancers whose X-Forwarded-For is believed " +
          "when resolving a heartbeat's client address. Empty = believe none."
    )
    trustedProxies: String = "",
    @field @ConfigField(
      envVar = "QOD_FLEET_NODE_PG_HOST",
      description =
        "Address fleet servers dial for the managed Postgres, when it differs from the manager's " +
          "own (e.g. the manager reaches it by a container-only name). Replaces pgHost in fleet " +
          "assignments of databases on the default metastore only. Empty = unchanged."
    )
    nodePgHost: String = "",
    @field @ConfigField(
      envVar = "QOD_FLEET_NODE_PG_PORT",
      description =
        "Port fleet servers dial for the managed Postgres; replaces pgPort only where it equals " +
          "the default metastore's. Empty = unchanged."
    )
    nodePgPort: String = ""
):
  require(heartbeatSec >= 1, "fleet: heartbeatSec must be >= 1")
  require(Set("fleet", "local").contains(ephemeral), "fleet: ephemeral must be 'fleet' or 'local'")
  require(heartbeatTimeoutSec > heartbeatSec, "fleet: heartbeatTimeoutSec must be > heartbeatSec")
  require(
    reassignAfterSec == 0 || reassignAfterSec == -1 || reassignAfterSec >= heartbeatTimeoutSec,
    "fleet: reassignAfterSec must be 0, -1, or >= heartbeatTimeoutSec"
  )
  require(startupTimeoutSec >= 1, "fleet: startupTimeoutSec must be >= 1")
  require(stopTimeoutSec >= 1, "fleet: stopTimeoutSec must be >= 1")

  // Validated here so a bad value refuses boot. `def`s, not vals: ConfigRegistry pairs declared
  // fields with constructor parameters by position.
  FleetConfig
    .parseCidrs("QOD_FLEET_AUTO_APPROVE", autoApprove)
    .left
    .foreach(e => throw new IllegalArgumentException(e))
  FleetConfig
    .parseCidrs("QOD_FLEET_TRUSTED_PROXIES", trustedProxies)
    .left
    .foreach(e => throw new IllegalArgumentException(e))

  def autoApproveCidrs: List[Cidr] =
    FleetConfig.parseCidrs("QOD_FLEET_AUTO_APPROVE", autoApprove).fold(e => sys.error(e), identity)

  def trustedProxyCidrs: List[Cidr] =
    FleetConfig
      .parseCidrs("QOD_FLEET_TRUSTED_PROXIES", trustedProxies)
      .fold(e => sys.error(e), identity)

  /** The boot WARN when auto-approval admits a whole address family; None otherwise. */
  def openAutoApproveWarning: Option[String] =
    Option.when(autoApproveCidrs.exists(_.isEverything))(
      "fleet auto-approve is open to every address: any holder of the join token is approved " +
        "on join. Set QOD_FLEET_AUTO_APPROVE to restrict."
    )

  def ephemeralLocal: Boolean = ephemeral == "local"

  /** QOD_FLEET_EPHEMERAL=local also needs qod-node on the manager host: Main resolves it for every
    * manager that runs local nodes (BootFactories.runsLocalNodes) and refuses to boot without it.
    */
  def validateForRuntime(runtimeType: String): Either[String, Unit] =
    if !FleetConfig.isFleet(runtimeType) then Right(())
    else if joinToken.trim.isEmpty then
      Left("QOD_FLEET_JOIN_TOKEN must be set when runtimeType=fleet")
    else Right(())

/** The names `runtimeType` (QOD_RUNTIME_TYPE) accepts, in any case: one predicate per backend,
  * which every site that tests the name goes through, so an alias (`k8s`) reaches every check.
  */
object RuntimeType:
  private def name(runtimeType: String): String = runtimeType.toLowerCase(java.util.Locale.ROOT)

  def isLocal(runtimeType: String): Boolean = name(runtimeType) == "local"

  def isKubernetes(runtimeType: String): Boolean =
    val n = name(runtimeType)
    n == "kubernetes" || n == "k8s"

  def isFleet(runtimeType: String): Boolean = name(runtimeType) == "fleet"

  /** The backends several managers can share (HA): Kubernetes and fleet. */
  def isMultiManager(runtimeType: String): Boolean =
    isKubernetes(runtimeType) || isFleet(runtimeType)

object FleetConfig:
  def isFleet(runtimeType: String): Boolean = RuntimeType.isFleet(runtimeType)

  def parseCidrs(env: String, raw: String): Either[String, List[Cidr]] =
    Cidr.parseList(raw).left.map(e => s"$env: $e")

/** Writable branches of DuckLake tenant-dbs (Epic 1): a branch is a cloned catalog served by its
  * own one-node pool; agents write there, a human reviews the change set and fast-forward merges.
  * `enabled = false` refuses every branch endpoint and tool and starts no expiry sweep.
  */
final case class BranchingConfig(
    @field @ConfigField(
      envVar = "QOD_BRANCH_ENABLED",
      description = "Global kill switch for branching (endpoints, MCP tools, expiry sweep)."
    )
    enabled: Boolean = true,
    @field @ConfigField(
      envVar = "QOD_BRANCH_DEFAULT_TTL_HOURS",
      description =
        "Default time-to-live of a branch in hours when the creator sets none; an expired branch " +
          "is discarded by the leader's sweep. 0 = branches never expire by default."
    )
    defaultTtlHours: Int = 168,
    @field @ConfigField(
      envVar = "QOD_BRANCH_SWEEP_SEC",
      description = "Expiry sweep interval in seconds; clamped to a 60s floor."
    )
    sweepSec: Int = 300,
    @field @ConfigField(
      envVar = "QOD_BRANCH_MAX_PER_DATABASE",
      description = "Maximum live (open or proposed) branches per tenant-db."
    )
    maxPerDatabase: Int = 20,
    @field @ConfigField(
      envVar = "QOD_BRANCH_MERGE_TIMEOUT_SEC",
      description =
        "Bounded wait for the merge transaction on the ephemeral merge node before the request " +
          "fails; the commit is probed afterwards so a late commit is still recorded."
    )
    mergeTimeoutSec: Int = 600,
    @field @ConfigField(
      envVar = "QOD_BRANCH_NODE_READY_TIMEOUT_SEC",
      description = "How long to wait for the ephemeral merge node to accept connections."
    )
    nodeReadyTimeoutSec: Int = 120
):
  require(defaultTtlHours >= 0, "branching: defaultTtlHours must be >= 0")
  require(maxPerDatabase >= 1, "branching: maxPerDatabase must be >= 1")
  require(mergeTimeoutSec >= 1, "branching: mergeTimeoutSec must be >= 1")
  def sweepInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(math.max(60, sweepSec)).seconds

/** Persistent embedded Postgres for the control plane: the zero-prerequisite single-node mode
  * `qod serve` launches. Distinct from the ephemeral demo instance
  * ([[ai.starlake.quack.ondemand.demo.DemoPostgres]]) in three ways that matter: the data directory
  * is reused across restarts, it is never deleted, and a stale `postmaster.pid` naming a dead
  * process falls through to ordinary Postgres crash recovery instead of a wipe.
  */
final case class EmbeddedPostgresConfig(
    @field @ConfigField(
      envVar = "QOD_PG_EMBEDDED",
      description =
        "Run the control plane on a bundled embedded Postgres rooted at dataDir instead of an " +
          "external server. Single-node evaluation / small-team mode; refused under HA."
    )
    enabled: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_PG_EMBEDDED_PORT",
      description =
        "Fixed TCP port for the embedded Postgres. Fixed rather than OS-assigned so the " +
          "coordinates stay stable across restarts and psql works for support."
    )
    port: Int = 25432,
    @field @ConfigField(
      envVar = "QOD_PG_EMBEDDED_DATA_DIR",
      description =
        "Directory holding the embedded Postgres data directory. Empty means the platform " +
          "user-data dir (<user-data-dir>/pg)."
    )
    dataDir: String = ""
):
  require(port > 0 && port <= 65535, s"embeddedPostgres: port must be 1..65535, got $port")

/** Managed object storage: one operator root bucket, one prefix per tenant-db incarnation. Fills
  * the database's objectStore from these credentials at managed create; a background worker purges
  * deleted prefixes after retainDays. See
  * docs/superpowers/specs/2026-08-13-managed-object-storage-design.md.
  */
final case class ManagedObjectStoreConfig(
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_ENABLED",
      description = "Global kill switch for managed object storage. Off = managed creates 400."
    )
    enabled: Boolean = false,
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_ENDPOINT",
      description = "S3-compatible endpoint URL for the operator root bucket."
    )
    endpoint: String = "",
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_REGION",
      description = "Region passed to the S3-compatible client."
    )
    region: String = "us-east-1",
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_BUCKET",
      description = "Operator root bucket; each tenant-db incarnation gets its own prefix."
    )
    bucket: String = "qod-managed",
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_ACCESS_KEY_ID",
      description = "Access key id for the operator root bucket."
    )
    accessKeyId: String = "",
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_SECRET_ACCESS_KEY",
      description = "Secret access key for the operator root bucket.",
      sensitive = true
    )
    secretAccessKey: String = "",
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_URL_STYLE",
      description = "Bucket addressing style presented to clients: 'path' or 'vhost'."
    )
    urlStyle: String = "path",
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_RETAIN_DAYS",
      description =
        "Days a deleted tenant-db prefix is retained before the purge worker removes it."
    )
    retainDays: Int = 7,
    @field @ConfigField(
      envVar = "QOD_MANAGED_STORE_PURGE_SWEEP_SEC",
      description = "Purge worker sweep interval in seconds; clamped to a 60s floor."
    )
    purgeSweepSec: Int = 300
):
  require(retainDays >= 0, "managedObjectStore: retainDays must be >= 0")
  require(
    urlStyle == "path" || urlStyle == "vhost",
    "managedObjectStore: urlStyle must be path or vhost"
  )
  def purgeSweepInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(math.max(60, purgeSweepSec)).seconds

/** SMTP delivery for account-lockout reset emails. `host` unset (the default) keeps `Main` wiring
  * `LogMailSender`; setting `QOD_SMTP_HOST` switches to `SmtpMailSender`. Task 8's boot gate also
  * reads `host` to decide whether lockout can be enabled.
  */
final case class SmtpConfig(
    @field @ConfigField(
      envVar = "QOD_SMTP_HOST",
      description = "SMTP relay host. Unset keeps the manager on the log-only mail sender."
    )
    host: Option[String] = None,
    @field @ConfigField(
      envVar = "QOD_SMTP_PORT",
      description = "SMTP relay port."
    )
    port: Int = 587,
    @field @ConfigField(
      envVar = "QOD_SMTP_USER",
      description = "SMTP auth username. Unset disables SMTP auth."
    )
    user: Option[String] = None,
    @field @ConfigField(
      envVar = "QOD_SMTP_PASSWORD",
      description = "SMTP auth password.",
      sensitive = true
    )
    password: Option[String] = None,
    @field @ConfigField(
      envVar = "QOD_SMTP_FROM",
      description = "From address stamped on outgoing mail."
    )
    from: String = "no-reply@quack-on-demand.local",
    @field @ConfigField(
      envVar = "QOD_SMTP_STARTTLS",
      description = "Use STARTTLS when connecting to the SMTP relay."
    )
    starttls: Boolean = true
)

final case class FlightConfig(
    @field @ConfigField(envVar = "PROXY_HOST", description = "FlightSQL edge bind address.")
    host: String,
    @field @ConfigField(envVar = "PROXY_PORT", description = "FlightSQL edge port.")
    port: Int,
    @field @ConfigField(
      envVar = "PROXY_TLS_ENABLED",
      description = "Enable TLS on the FlightSQL edge."
    )
    tlsEnabled: Boolean,
    @field @ConfigField(
      envVar = "PROXY_TLS_CERT_CHAIN",
      description = "Path to the TLS certificate chain PEM (auto-generated if missing)."
    )
    tlsCertChain: String,
    @field @ConfigField(
      envVar = "PROXY_TLS_PRIVATE_KEY",
      description = "Path to the TLS private key PEM (auto-generated if missing)."
    )
    tlsPrivateKey: String,
    @field @ConfigField(
      envVar = "QOD_SESSION_TTL_SEC",
      description = "Edge session TTL in seconds before a fresh handshake is forced."
    )
    sessionTtlSec: Long,
    @field @ConfigField(
      envVar = "PROXY_RESUME_HOLD_TIMEOUT_SEC",
      description = "Max seconds the edge holds a statement while a suspended pool cold-starts."
    )
    resumeHoldTimeoutSec: Long
)

/** The native Quack protocol front door (`quack-native` block): the listener DuckDB clients
  * `ATTACH 'quack:host:port'` to. Identity, routing, policies and audit are the FlightSQL edge's;
  * only the wire differs. See docs/superpowers/specs/2026-09-20-native-quack-front-door-design.md.
  */
final case class QuackNativeConfig(
    @field @ConfigField(
      envVar = "QOD_QUACK_ENABLED",
      description = "Serve the native Quack protocol front door."
    )
    enabled: Boolean,
    @field @ConfigField(envVar = "QOD_QUACK_HOST", description = "Quack front door bind address.")
    host: String,
    @field @ConfigField(
      envVar = "QOD_QUACK_PORT",
      description =
        "Quack front door port (9494 is the protocol's default, so `quack:host` needs no port)."
    )
    port: Int,
    @field @ConfigField(
      envVar = "QOD_QUACK_TLS_ENABLED",
      description =
        "Enable TLS on the Quack front door. Off by default: the DuckDB client only speaks plain HTTP to loopback hosts."
    )
    tlsEnabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_QUACK_TLS_CERT_CHAIN",
      description =
        "Path to the TLS certificate chain PEM (shared with the FlightSQL edge by default)."
    )
    tlsCertChain: String,
    @field @ConfigField(
      envVar = "QOD_QUACK_TLS_PRIVATE_KEY",
      description = "Path to the TLS private key PEM (PKCS8)."
    )
    tlsPrivateKey: String,
    @field @ConfigField(
      envVar = "QOD_QUACK_MAX_HEARTBEAT_SEC",
      description = "Cap on the heartbeat lease a client may request, in seconds."
    )
    maxHeartbeatTimeoutSec: Long,
    @field @ConfigField(
      envVar = "QOD_QUACK_MAX_BODY_BYTES",
      description =
        "Largest request body accepted on /quack, in bytes (appends and streamed inserts)."
    )
    maxBodyBytes: Long
)
