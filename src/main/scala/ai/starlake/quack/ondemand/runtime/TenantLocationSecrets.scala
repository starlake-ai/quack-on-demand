package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.TenantLocation

/** The node side of a tenant's authorized S3 locations: one DuckDB secret per bucket, named
  * `qod_tl_<first 8 of the location id>` and scoped to `s3://<bucket>/`, so the tenant's sessions
  * reach a bucket only with the keys the tenant gave for it. DuckDB picks the longest matching
  * scope, so a database's own `qod_db_store` secret (scoped to its data path) still wins there.
  * Delivered at spawn as the node secret file `tenantStoreSql` ([[initSql]], one statement per
  * line) and pushed to running nodes on a change ([[pushAdd]], [[pushRemove]]). The statements
  * carry credentials: nothing here, or at a caller, logs them.
  */
object TenantLocationSecrets:

  def name(l: TenantLocation): String = s"qod_tl_${l.id.take(8)}"

  def createSql(l: TenantLocation): String =
    ObjectStoreSecret.s3Statement(name(l), l.objectStore, l.location)

  def dropSql(l: TenantLocation): String = s"DROP SECRET IF EXISTS ${name(l)};"

  /** The `tenantStoreSql` of a node of a tenant holding `locations`; "" when none is in S3. */
  def initSql(locations: List[TenantLocation]): String =
    val s3 = locations.filter(_.kind == TenantLocation.S3).sortBy(_.location)
    if s3.isEmpty then ""
    else ("INSTALL httpfs;" :: "LOAD httpfs;" :: s3.map(createSql)).mkString("", "\n", "\n")

  /** What a running node of the tenant runs once `l` is added. */
  def pushAdd(l: TenantLocation): List[String] =
    if l.kind == TenantLocation.S3 then List("INSTALL httpfs;", "LOAD httpfs;", createSql(l))
    else Nil

  /** What a running node of the tenant runs once `l` is removed. */
  def pushRemove(l: TenantLocation): List[String] =
    if l.kind == TenantLocation.S3 then List(dropSql(l)) else Nil
