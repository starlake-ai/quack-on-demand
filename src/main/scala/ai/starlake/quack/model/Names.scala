package ai.starlake.quack.model

/** Naming policy for human-supplied identifiers (tenant displayName, tenant-db suffix, pool name).
  *
  * Names follow **Postgres-identifier rules** so they can serve as Postgres object names without
  * quoting:
  *
  *   - Case-insensitive on input; stored in lowercase.
  *   - Allowed characters: ASCII letters, digits, underscore.
  *   - First character: letter or underscore.
  *   - Length: 1..63 bytes (Postgres `NAMEDATALEN - 1`).
  */
object Names:

  val MaxLength: Int  = 63
  private val Pattern = "^[a-zA-Z_][a-zA-Z0-9_]*$".r

  /** Shape of a surrogate tenant id minted by [[newSurrogateId]]: literal `t-` followed by 8+
    * lowercase hex chars. Ids minted today are 32 hex chars (UUID with dashes stripped, ~128 bits
    * of entropy); the open-ended length lets shorter ids that already exist in the database keep
    * matching. This is the pattern used to disambiguate the FlightSQL `tenant` connection-string
    * param -- a caller may pass either the display name (`tpch`) or the surrogate id (`t-02d0e86e`
    * / `t-02d0e86e9c5d4a3b8f6e1c2d4a3b8f6e`); the two shapes never overlap because display names
    * cannot start with `t-` (hyphen is not in the identifier alphabet).
    */
  private val TenantIdPattern = "^t-[0-9a-f]{8,}$".r

  def isValid(raw: String): Boolean =
    raw != null && raw.length >= 1 && raw.length <= MaxLength && Pattern.matches(raw)

  /** Whether `raw` matches the tenant-id surrogate shape. Display names cannot match because they
    * exclude hyphens; tenant ids cannot match `isValid` for the same reason. The two spaces are
    * disjoint.
    */
  def looksLikeTenantId(raw: String): Boolean =
    raw != null && TenantIdPattern.matches(raw)

  /** Lowercase + validate; throws on a bad input. Used at supervisor entry points where invalid
    * input is a caller bug.
    */
  def normalize(raw: String, label: String = "name"): String =
    if !isValid(raw) then throw new IllegalArgumentException(reject(raw, label))
    else raw.toLowerCase

  /** Either-flavour for REST handlers that want to surface the error. */
  def normalizeOrError(raw: String, label: String = "name"): Either[String, String] =
    if !isValid(raw) then Left(reject(raw, label)) else Right(raw.toLowerCase)

  /** Compose a tenant-db name from a tenant + a user-supplied suffix. The output is the actual
    * Postgres database name on the shared server, so we always insert a `_` between tenant and
    * suffix.
    *
    *   - `(tenant="tpch", suffix="tpch1")` -> `Right("tpch_tpch1")`
    *   - `(tenant="tpch", suffix="tpch_tpch1")` is idempotent (input is already prefixed) and
    *     returns `Right("tpch_tpch1")`.
    *   - suffix == tenant is rejected (DuckDB rejects ATTACH-ing a catalog under the same name as
    *     an existing catalog/database).
    */
  def normalizeTenantDbName(tenantName: String, suffix: String): Either[String, String] =
    for
      t <- normalizeOrError(tenantName, "tenant name")
      s <- normalizeOrError(suffix, "tenant-db name")
      _ <-
        if s == t then
          Left(
            s"tenant-db name '$s' must not equal the tenant name " +
              "(DuckDB rejects attaching a catalog under the same name " +
              "as an existing catalog/database)"
          )
        else Right(())
      full = if s.startsWith(t + "_") then s else s"${t}_${s}"
      _ <-
        if full.length <= MaxLength then Right(())
        else Left(s"composed tenant-db name '$full' exceeds $MaxLength chars")
    yield full

  private def reject(raw: String, label: String): String =
    s"invalid $label '${if raw == null then "<null>" else raw}': must be 1..$MaxLength chars, " +
      "start with a letter or underscore, and contain only letters, digits and underscore " +
      "(Postgres identifier rules)"

  /** Mint a surrogate row id with the given prefix (e.g. `t`, `td`, `p`, `r`, `g`, `rp`, `pp`,
    * `fs`, `fsec`, `u`). Format: `<prefix>-<32 lowercase hex chars>` -- a UUID with dashes
    * stripped, ~128 bits of entropy.
    */
  def newSurrogateId(prefix: String): String =
    s"$prefix-${java.util.UUID.randomUUID().toString.replace("-", "")}"
