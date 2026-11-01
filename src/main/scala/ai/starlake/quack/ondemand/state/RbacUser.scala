package ai.starlake.quack.ondemand.state

import java.time.Instant

/** A row in `qodstate_user`. `tenant = None` is the superuser marker -- a row with tenant IS NULL
  * bypasses the pool-access and per-statement ACL gates at the FlightSQL edge. Tenant-scoped
  * principals carry a non-empty tenant; uniqueness is enforced by the partial unique index on
  * `(tenant, username) WHERE tenant IS NOT NULL`.
  *
  * `kind` is the account kind (`admin` | `user`): `admin` grants management rights (UI, REST admin
  * endpoints, SQL admin statements). It does NOT participate in RBAC role resolution, which lives
  * in `qodstate_role`.
  */
final case class RbacUser(
    id: String,
    tenant: Option[String],
    username: String,
    kind: String,
    enabled: Boolean = true,
    mustChangePassword: Boolean = false,
    email: Option[String] = None,
    createdAt: Option[Instant] = None,
    updatedAt: Option[Instant] = None,
    // SCIM 2.0 externalId: the provisioning IdP's identifier, set only through the
    // /api/scim surface (never by REST/CLI/manifest). None = not IdP-managed.
    externalId: Option[String] = None
)

/** The account kinds a row may hold, and the one spelling each is stored under. */
object UserKind:
  val Admin: String    = "admin"
  val User: String     = "user"
  val All: Set[String] = Set(Admin, User)

  /** `raw` trimmed and lowercased when it names a kind; else why not. Every writer stores the
    * normalized value: a guard that compares a trimmed value while the row keeps the raw one (`"
    * admin "`) would pass a write that leaves no recognized admin.
    */
  def normalize(raw: String): Either[String, String] =
    val k = raw.trim.toLowerCase(java.util.Locale.ROOT)
    if All.contains(k) then Right(k)
    else Left(s"kind must be one of ${All.toList.sorted.mkString(", ")}, got '$raw'")
