package ai.starlake.acl.api

import java.time.Instant

/** SQL context parameters provided per-call to `checkAccess` / `checkAccessAll`.
  *
  * Groups the SQL-related settings that may vary between calls on the same
  * `AclSql` instance: the default database and schema used to qualify
  * unqualified table references, and the SQL dialect.
  *
  * @param defaultDatabase
  *   Default database for unqualified table references (None = require fully qualified)
  * @param defaultSchema
  *   Default schema for unqualified table references (None = require fully qualified)
  * @param dialect
  *   SQL dialect name (e.g., "duckdb", "ansi")
  * @param now
  *   Evaluation timestamp for grant expiration checks (None = use Instant.now())
  */
final case class SqlContext(
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    dialect: String = "duckdb",
    now: Option[Instant] = None,
    maxViewDepth: Int = 50
)

object SqlContext:
  /** Default SQL context: no database, no schema, DuckDB dialect. */
  val default: SqlContext = SqlContext()
