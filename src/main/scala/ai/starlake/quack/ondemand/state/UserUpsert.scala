package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.Names

import java.sql.{Connection, Types}

/** The single `(tenant, username)`-keyed upsert into `qodstate_user`, shared by [[UserStore]] (its
  * own Hikari pool) and [[PostgresControlPlaneStore]] (the control-plane pool). Both stores own
  * separate connection pools, so this operates on a caller-supplied [[Connection]] rather than
  * holding one. Keeping the lookup + column set in exactly one place is what stops the two call
  * sites from drifting -- the `enabled` column silently becoming a no-op on one path is precisely
  * the bug this consolidation removes.
  */
object UserUpsert:

  /** Outcome: the persisted id and whether the row was freshly inserted (`true`) or an existing row
    * was refreshed (`false`).
    */
  final case class Result(id: String, inserted: Boolean)

  /** Look the row up by `(tenant, username)` first so an update preserves the id (RBAC edges and
    * pool grants keep their FK target across rotations), then id-keyed upsert. The partial unique
    * indexes (admin `tenant IS NULL` vs scoped `(tenant, username)`) mean a single `ON CONFLICT`
    * target can't be named without knowing the tenant kind, so the lookup is the cleanest path.
    *
    * `enabled` controls the disabled flag:
    *   - `Some(b)`: write `enabled = b` on both insert and update.
    *   - `None`: leave `enabled` to the column default on insert and PRESERVE the stored value on
    *     update -- the plain credential/role rotation path must never silently re-enable a disabled
    *     user.
    */
  def apply(
      c: Connection,
      tenant: Option[String],
      username: String,
      passwordHash: String,
      role: String,
      enabled: Option[Boolean]
  ): Result =
    val existing = lookupId(c, tenant, username)
    val id       = existing.getOrElse(Names.newSurrogateId("u"))
    val sql      = enabled match
      case Some(_) =>
        """INSERT INTO qodstate_user (id, tenant, username, password_hash, role, enabled, updated_at)
          |VALUES (?, ?, ?, ?, ?, ?, NOW())
          |ON CONFLICT (id) DO UPDATE SET
          |  password_hash = EXCLUDED.password_hash,
          |  role          = EXCLUDED.role,
          |  enabled       = EXCLUDED.enabled,
          |  updated_at    = NOW()""".stripMargin
      case None =>
        """INSERT INTO qodstate_user (id, tenant, username, password_hash, role, updated_at)
          |VALUES (?, ?, ?, ?, ?, NOW())
          |ON CONFLICT (id) DO UPDATE SET
          |  password_hash = EXCLUDED.password_hash,
          |  role          = EXCLUDED.role,
          |  updated_at    = NOW()""".stripMargin
    val ps = c.prepareStatement(sql)
    try
      ps.setString(1, id)
      tenant match
        case Some(t) => ps.setString(2, t)
        case None    => ps.setNull(2, Types.VARCHAR)
      ps.setString(3, username)
      ps.setString(4, passwordHash)
      ps.setString(5, role)
      enabled.foreach(b => ps.setBoolean(6, b))
      ps.executeUpdate()
      Result(id = id, inserted = existing.isEmpty)
    finally ps.close()

  private def lookupId(c: Connection, tenant: Option[String], username: String): Option[String] =
    val ps = tenant match
      case Some(t) =>
        val p = c.prepareStatement(
          "SELECT id FROM qodstate_user WHERE tenant = ? AND username = ?"
        )
        p.setString(1, t)
        p.setString(2, username)
        p
      case None =>
        val p = c.prepareStatement(
          "SELECT id FROM qodstate_user WHERE tenant IS NULL AND username = ?"
        )
        p.setString(1, username)
        p
    try
      val rs = ps.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally ps.close()
