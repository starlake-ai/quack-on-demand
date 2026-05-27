package ai.starlake.acl.api

import java.time.Instant

/** Status of a tenant's cached grants.
  *
  * Fresh: Successfully loaded at the given time. Stale: Last successful load at
  * loadedAt, reload failed at failedAt. NotLoaded: Tenant has never been
  * accessed.
  */
enum TenantStatus:
  /** Grants are fresh, successfully loaded at the given time. */
  case Fresh(loadedAt: Instant)

  /** Grants are stale: last successful load at loadedAt, reload failed at
    * failedAt with the given error message.
    */
  case Stale(loadedAt: Instant, failedAt: Instant, errorMsg: String)

  /** Tenant has never been accessed (no grants loaded). */
  case NotLoaded
