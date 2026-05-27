package ai.starlake.acl.api

/** Configuration for AclSql tenant-aware API.
  *
  * Contains only tenant cache settings. SQL context (database, schema, dialect)
  * is provided per-call via `SqlContext`.
  *
  * @param maxTenants
  *   Maximum cached tenants (None = unlimited, enables LRU eviction when set)
  */
final case class AclSqlConfig(
    maxTenants: Option[Int] = None
)

object AclSqlConfig:
  /** Default configuration with unlimited tenants. */
  val default: AclSqlConfig = AclSqlConfig()
