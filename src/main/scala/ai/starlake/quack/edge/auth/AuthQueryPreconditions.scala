package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.DatabaseAuthConfig

import java.sql.Connection

/** Config-load-time gate for `DatabaseAuthConfig.systemQuery` / `tenantQuery`: both MUST project
  * exactly three columns `(password_hash, role, enabled)`, mirroring the runtime enforcement in
  * [[DatabaseAuthenticator]] (a result set with fewer than three columns fails every login).
  * Catching a misconfigured custom query at startup is cheaper than discovering it the first time
  * an operator wonders why disabling a user does not lock them out.
  *
  * Uses `PreparedStatement.getMetaData` -- the Postgres JDBC driver resolves column metadata via
  * the wire-protocol `Describe` message without executing the statement or binding parameters, so
  * this is a cheap, side-effect-free check.
  */
object AuthQueryPreconditions:

  private val RequiredColumns = 3

  def validate(conn: Connection, config: DatabaseAuthConfig): Either[String, Unit] =
    if !config.enabled then Right(())
    else
      checkQuery(conn, config.systemQuery, "systemQuery (QOD_AUTH_DB_SYSTEM_QUERY)")
        .flatMap(_ =>
          checkQuery(conn, config.tenantQuery, "tenantQuery (QOD_AUTH_DB_TENANT_QUERY)")
        )

  private def checkQuery(conn: Connection, query: String, label: String): Either[String, Unit] =
    val ps = conn.prepareStatement(query)
    try
      val n = ps.getMetaData.getColumnCount
      if n < RequiredColumns then
        Left(
          s"auth.database.$label must project $RequiredColumns columns " +
            "(password_hash, role, enabled) -- the enabled column is mandatory " +
            s"(this query currently projects only $n)"
        )
      else Right(())
    finally ps.close()
