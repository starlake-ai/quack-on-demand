package ai.starlake.quack.model

/** Naming policy for human-supplied identifiers (tenant displayName,
  * tenant-db suffix, pool name).
  *
  * Names follow **Postgres-identifier rules** so they can serve as
  * Postgres object names without quoting:
  *
  *   - Case-insensitive on input; stored in lowercase.
  *   - Allowed characters: ASCII letters, digits, underscore.
  *   - First character: letter or underscore.
  *   - Length: 1..63 bytes (Postgres `NAMEDATALEN - 1`). */
object Names:

  val MaxLength: Int = 63
  private val Pattern = "^[a-zA-Z_][a-zA-Z0-9_]*$".r

  def isValid(raw: String): Boolean =
    raw != null && raw.length >= 1 && raw.length <= MaxLength && Pattern.matches(raw)

  /** Lowercase + validate; throws on a bad input. Used at supervisor
    * entry points where invalid input is a caller bug. */
  def normalize(raw: String, label: String = "name"): String =
    if !isValid(raw) then throw new IllegalArgumentException(reject(raw, label))
    else raw.toLowerCase

  /** Either-flavour for REST handlers that want to surface the error. */
  def normalizeOrError(raw: String, label: String = "name"): Either[String, String] =
    if !isValid(raw) then Left(reject(raw, label)) else Right(raw.toLowerCase)

  /** Compose a tenant-db name from a tenant + a user-supplied suffix.
    * The output is the actual Postgres database name on the shared
    * server, so we always insert a `_` between tenant and suffix.
    *
    *   - `(tenant="tpch", suffix="tpch1")`  -> `Right("tpch_tpch1")`
    *   - `(tenant="tpch", suffix="tpch_tpch1")` is idempotent (input is
    *     already prefixed) and returns `Right("tpch_tpch1")`.
    *   - suffix == tenant is rejected (DuckDB rejects ATTACH-ing a
    *     catalog under the same name as an existing catalog/database). */
  def normalizeTenantDbName(tenantName: String, suffix: String): Either[String, String] =
    for
      t <- normalizeOrError(tenantName, "tenant name")
      s <- normalizeOrError(suffix,     "tenant-db name")
      _ <- if s == t then Left(
             s"tenant-db name '$s' must not equal the tenant name " +
             "(DuckDB rejects attaching a catalog under the same name " +
             "as an existing catalog/database)"
           ) else Right(())
      full = if s.startsWith(t + "_") then s else s"${t}_${s}"
      _ <- if full.length <= MaxLength then Right(())
           else Left(s"composed tenant-db name '$full' exceeds $MaxLength chars")
    yield full

  private def reject(raw: String, label: String): String =
    s"invalid $label '${if raw == null then "<null>" else raw}': must be 1..$MaxLength chars, " +
      "start with a letter or underscore, and contain only letters, digits and underscore " +
      "(Postgres identifier rules)"
