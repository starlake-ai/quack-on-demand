package ai.starlake.quack.ondemand.security

/** What the stored-state checks read from the control plane. Every method blocks (JDBC or the
  * supervisor's caches): checks call them inside `IO.blocking`. Names, never values.
  */
trait AuditFacts:
  /** Whether the control-plane login is a Postgres superuser. */
  def controlPlaneLoginIsSuperuser(): Boolean

  /** Enabled tenant users whose only role is `qod_all_tables` and only group `qod_all_pools`, as
    * (tenant, count), sorted by tenant.
    */
  def permissiveBuiltinUsers(): List[(String, Int)]

  /** Unrevoked, unexpired PATs owned by a disabled account. */
  def dormantPatsOfDisabledUsers(): Int

  /** Federated sources holding at least one secret and not read-only, as `tenant/database/alias`.
    */
  def writableSourcesWithSecrets(): List[String]

  /** Live branches (`open`, `proposed`) with no TTL, as `tenant/database/branch`. */
  def liveBranchesWithoutTtl(): List[String]

  /** Approved fleet servers with no `approved_source`, by name. */
  def unboundApprovedServers(): List[String]

  /** Unencrypted DuckLake and duckdb-file databases, branches excluded, as (tenant, count). */
  def unencryptedDatabases(): List[(String, Int)]

  /** Databases whose resolved data path is local, as `tenant/database`. */
  def localDatabases(): List[String]

  /** Databases and pools holding untrusted initSql, as `tenant/label` (`InitSqlTrustReport.Row`).
    */
  def untrustedInitSql(): List[String]

  /** Tenants whose console sign-in resolves to OIDC, by id. */
  def oidcTenants(): List[String]
