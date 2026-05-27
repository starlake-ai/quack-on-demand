package ai.starlake.acl.watcher

import ai.starlake.acl.model.TenantId

/** Callback trait for TenantWatcher events.
  *
  * All three methods are required (no defaults). Implementations must be fast
  * and non-blocking - heavy work should be deferred. Exceptions are caught and
  * logged; watcher continues.
  */
trait TenantListener:
  /** Called when files in a tenant folder change. */
  def onInvalidate(tenantId: TenantId): Unit

  /** Called when a new tenant folder appears. */
  def onNewTenant(tenantId: TenantId): Unit

  /** Called when a tenant folder is deleted. */
  def onTenantDeleted(tenantId: TenantId): Unit
