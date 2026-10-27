package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.TenantLocation

/** The node side of a tenant's authorized S3 locations: one DuckDB secret per bucket, named
  * `qod_tl_<first 8 of the location id>` and scoped to `s3://<bucket>/`, so the tenant's sessions
  * reach a bucket only with the keys the tenant gave for it. DuckDB picks the s3 secret with the
  * longest matching scope, a tie going to the name first in alphabetical order. A bucket scope
  * therefore outranks the unscoped `quack_s3` for every path in the bucket, so the database's own
  * credentials are scoped to its data path (ObjectStoreSecret.dataScope): `qod_db_store`
  * (ObjectStoreSecret, the database's objectStore) or else `qod_data_s3` (the spawn scripts, the
  * manager's `QOD_S3_*` keys). That scope is longer than a bucket scope, or ties it at a bucket
  * root, a tie both names win since they sort before `qod_tl_`. So a database whose data path lies
  * in an authorized bucket keeps signing with its own keys, with two exceptions:
  *   - a branch of a bucket-root database (see ObjectStoreSecret.dataScope);
  *   - a database whose data-path credentials come from neither its objectStore nor the manager's
  *     `QOD_S3_*` keys (a `credential_chain` secret in its init SQL, or DuckDB's defaults): no
  *     secret of its own is scoped to its data path, so once the tenant authorizes that bucket the
  *     database's data signs with the tenant's keys. A known limitation.
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

  /** What a running node of the tenant runs once `l` is added. No `INSTALL`: every spawn installs
    * httpfs before lockdown, and on a lockdown node whose data path is in an object store
    * (`disabled_filesystems = 'LocalFileSystem'`) an `INSTALL` fails, which would stop the push
    * before the secret. `LOAD` of an installed extension needs no local file write.
    */
  def pushAdd(l: TenantLocation): List[String] =
    if l.kind == TenantLocation.S3 then List("LOAD httpfs;", createSql(l))
    else Nil

  /** What a running node of the tenant runs once `l` is removed. */
  def pushRemove(l: TenantLocation): List[String] =
    if l.kind == TenantLocation.S3 then List(dropSql(l)) else Nil
