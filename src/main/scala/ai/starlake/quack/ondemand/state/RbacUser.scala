package ai.starlake.quack.ondemand.state

import java.time.Instant

/** A row in `qodstate_user`. `tenant = None` is the superuser marker -- a row with tenant IS NULL
  * bypasses the pool-access and per-statement ACL gates at the FlightSQL edge. Tenant-scoped
  * principals carry a non-empty tenant; uniqueness is enforced by the partial unique index on
  * `(tenant, username) WHERE tenant IS NOT NULL`.
  *
  * `role` is kept as a free-text label for back-compat with the `AuthenticatedProfile.role` JWT
  * claim. It does NOT participate in RBAC role resolution -- that lives in `qodstate_role`.
  */
final case class RbacUser(
    id: String,
    tenant: Option[String],
    username: String,
    role: String,
    enabled: Boolean = true,
    createdAt: Option[Instant] = None,
    updatedAt: Option[Instant] = None
)
