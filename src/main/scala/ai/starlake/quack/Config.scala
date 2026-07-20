package ai.starlake.quack

import ai.starlake.quack.config.ConfigField

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
      envVar = "QOD_K8S_POD_LABEL",
      description = "Label selector that identifies manager-owned node pods."
    )
    podLabel: String,
    @field @ConfigField(
      envVar = "QOD_POD_TEMPLATE_ENABLED",
      description =
        "Allow superusers to supply a full Pod-manifest YAML template for a pool's node pods. Off by default; raw manifests are cluster-level power."
    )
    podTemplateEnabled: Boolean
)

final case class AdminConfig(
    // Comma-separated list of admin usernames. All get the same password +
    // role on seed. Stored as a single string so a single env var can
    // override (HOCON env-var substitution can't inject a list).
    @field @ConfigField(
      envVar = "QOD_ADMIN_USERNAME",
      description = "Comma-separated admin usernames seeded into qodstate_user."
    )
    username: String,
    @field @ConfigField(
      envVar = "QOD_ADMIN_PASSWORD",
      description = "Bootstrap admin password (re-hashed on every boot).",
      sensitive = true
    )
    password: String,
    @field @ConfigField(
      envVar = "QOD_ADMIN_ROLE",
      description = "Role assigned to the bootstrap admin user."
    )
    role: String
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
        "HS256 secret used to sign UI session JWTs. Pin a stable value (>= 32 chars) to make " +
          "sessions survive manager restart and to share session state across replicas. Empty " +
          "= autogenerate a fresh 32-byte secret at boot (sessions die on restart, no horizontal " +
          "scale).",
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
        "When empty, derived from X-Forwarded-Proto / X-Forwarded-Host / Host."
    )
    publicBaseUrl: String = "",
    oidc: ManagementOidcConfig = ManagementOidcConfig()
)

final case class ManagerAuthConfig(
    management: ManagementAuthConfig
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
      description = "Enable active-active multi-replica manager mode (Kubernetes runtime only)."
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
    usageRetentionDays: Int = 400
)

object TelemetryConfig:
  def validate(store: String, stmtHistoryRetentionDays: Int): Either[String, Unit] =
    store match
      case "postgres" | "none" =>
        if stmtHistoryRetentionDays == 0 || stmtHistoryRetentionDays >= 2 then Right(())
        else
          Left(
            "telemetry.stmtHistoryRetentionDays must be 0 (keep forever) or >= 2: the daily" +
              " rollup recompute rebuilds whole-day buckets from raw rows"
          )
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
      envVar = "QOD_API_KEY",
      description = "Static admin API key sent as X-API-Key. Unset = REST namespace is open.",
      sensitive = true
    )
    apiKey: Option[String],
    @field @ConfigField(
      envVar = "QOD_RUNTIME_TYPE",
      description = "Quack node runtime backend: 'local' (child processes) or 'kubernetes'."
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
      envVar = "QOD_NATIVE_CLIENT",
      description =
        "Use the JNI-backed native Quack wire client. False falls back to the embedded path."
    )
    nativeClient: Boolean,
    @field @ConfigField(
      envVar = "QOD_STAMP_WRITES",
      description =
        "Stamp DuckLake snapshots created by FlightSQL DML/DDL with author and commit message " +
          "(native wire bracket; fail-open). Off = writes are never bracketed."
    )
    stampWrites: Boolean,
    @field @ConfigField(
      envVar = "QOD_NODE_DISABLE_SSL",
      description =
        "Disable TLS on the embedded path's quack_query() call. Ignored on the native path."
    )
    nodeDisableSsl: Boolean,
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
    @field @ConfigField(
      envVar = "QOD_SESSION_IDLE_TTL_SEC",
      description =
        "UI session idle TTL in seconds. A session unused for this long is dropped on the next " +
          "access; each successful access slides the window. Manager restart still invalidates " +
          "everything (sessions are heap-only)."
    )
    sessionIdleTtlSec: Int,
    defaultMetastore: DefaultMetastoreConfig,
    admin: AdminConfig,
    k8s: K8sConfig,
    federation: FederationConfig,
    auth: ManagerAuthConfig
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
