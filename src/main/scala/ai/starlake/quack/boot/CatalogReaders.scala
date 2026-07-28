package ai.starlake.quack.boot

import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, ReaderCacheSweeper}

/** Cached per-tenant-db DuckLake catalog readers, extracted from Main.bootManager.
  *
  * Only meaningful in postgres mode: the DuckLake catalog tables (ducklake_schema, ducklake_table,
  * ...) only exist in a Postgres metastore. One reader per tenant-db, cached so we don't reopen
  * Hikari on every request; `metastoreOf` reads the effective metastore (default <- tenant
  * overrides) the same way PoolSupervisor does for spawn-node.
  *
  * Eviction has two triggers:
  *   - `evict` is plugged into the supervisor's onTenantDbDeleted/onTenantDbChanged hooks:
  *     tenant-db delete (or cascaded delete via deleteTenant) both removes the entry and closes the
  *     underlying HikariCP pool. Same hook covers an operator deleting + recreating a tenant-db to
  *     rotate Postgres credentials -- the new reader picks up the new metastore on the next call.
  *   - An idle-eviction sweeper backstop: thousands of self-serve tenant-dbs would otherwise pin
  *     one HikariCP pool each forever. Runs process-local on every replica (not HA-singleton-gated)
  *     since the cache itself is process-local. `catalogReader.sweepIntervalMin` /
  *     `catalogReader.idleEvictMin` (QOD_CATALOG_READER_SWEEP_MIN /
  *     QOD_CATALOG_READER_IDLE_EVICT_MIN) tune the cadence and threshold.
  */
final class CatalogReaders(
    metastoreOf: (String, String) => Map[String, String],
    idleEvictMin: Long,
    sweepIntervalMin: Long
):
  private val cache =
    new java.util.concurrent.ConcurrentHashMap[
      (String, String),
      ReaderCacheSweeper.Entry[DuckLakeCatalogReader]
    ]()

  private val sweeper =
    new ReaderCacheSweeper[(String, String), DuckLakeCatalogReader](
      cache,
      closeReader = _.close(),
      idleEvict = java.time.Duration.ofMinutes(idleEvictMin)
    )

  private val sweeperExecutor =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "qod-catalog-reader-sweeper")
      t.setDaemon(true)
      t
    }
  sweeperExecutor.scheduleAtFixedRate(
    () => sweeper.sweep(): Unit,
    sweepIntervalMin,
    sweepIntervalMin,
    java.util.concurrent.TimeUnit.MINUTES
  )

  def get(tenant: String, tenantDb: String): DuckLakeCatalogReader =
    val entry = cache.computeIfAbsent(
      (tenant, tenantDb),
      { case (t, td) => new ReaderCacheSweeper.Entry(DuckLakeCatalogReader(metastoreOf(t, td))) }
    )
    entry.lastAccess = java.time.Instant.now()
    entry.reader

  def evict(tenant: String, tenantDb: String): Unit =
    val removed = cache.remove((tenant, tenantDb))
    if removed != null then
      try removed.reader.close()
      catch case _: Throwable => ()

  /** Shutdown-hook teardown: stop the idle-eviction sweeper before tearing down the cache it
    * sweeps, then close every cached reader's Hikari pool. The map shouldn't see new entries past
    * this point because serve() has already returned, but iterate defensively.
    */
  def closeAllAndShutdown(): Unit =
    sweeperExecutor.shutdownNow(): Unit
    val it = cache.values.iterator()
    while it.hasNext do
      val r = it.next()
      try r.reader.close()
      catch case _: Throwable => ()
    cache.clear()
