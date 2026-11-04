package ai.starlake.quack.model

import java.util.Locale

/** Naming policy for human-supplied identifiers (tenant displayName, tenant-db suffix, pool name).
  *
  * Names follow **Postgres-identifier rules** so they can serve as Postgres object names without
  * quoting:
  *
  *   - Case-insensitive on input; stored in lowercase.
  *   - Allowed characters: ASCII letters, digits, underscore.
  *   - First character: letter or underscore.
  *   - Length: 1..63 bytes (Postgres `NAMEDATALEN - 1`).
  *
  * A NEW name also holds to [[readsAsOneField]], and a new pool name to [[poolNameRefusal]]: node
  * ids and per-pool Secret names put `--` between names, and only that rule lets them be told
  * apart. Names already stored are never judged by it, and the manager's own (`__merge`,
  * `__br_<id8>`) are made by paths that do not ask.
  */
object Names:

  val MaxLength: Int  = 63
  private val Pattern = "^[a-zA-Z_][a-zA-Z0-9_]*$".r

  def isValid(raw: String): Boolean =
    raw != null && raw.length >= 1 && raw.length <= MaxLength && Pattern.matches(raw)

  /** Lowercase + validate; throws on a bad input. Used at supervisor entry points where invalid
    * input is a caller bug.
    */
  def normalize(raw: String, label: String = "name"): String =
    if !isValid(raw) then throw new IllegalArgumentException(reject(raw, label))
    else raw.toLowerCase(Locale.ROOT)

  /** Either-flavour for REST handlers that want to surface the error. */
  def normalizeOrError(raw: String, label: String = "name"): Either[String, String] =
    if !isValid(raw) then Left(reject(raw, label)) else Right(raw.toLowerCase(Locale.ROOT))

  private val TenantIdPattern = "^[a-zA-Z][a-zA-Z0-9]*$".r

  /** A new tenant's id, lowercased: letters and digits only, starting with a letter. A database is
    * named `<tenant>_<suffix>` ([[normalizeTenantDbName]]), so with no `_` in a tenant's name the
    * first `_` of a database name marks where the tenant's ends. Only creating a tenant applies
    * this: a name a manifest import brings, or one stored before the rule, stays as it is.
    */
  def normalizeTenantId(raw: String): Either[String, String] =
    if raw != null && raw.length <= MaxLength && TenantIdPattern.matches(raw) then
      Right(raw.toLowerCase(Locale.ROOT))
    else
      Left(
        s"invalid tenant id '${if raw == null then "<null>" else raw}': a tenant name holds " +
          s"letters and digits only, starts with a letter and is at most $MaxLength characters. " +
          "Its databases are named <tenant>_<database>, and this keeps two tenants from ever " +
          "composing the same database name"
      )

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

  /** How a name reads inside a node id or a per-pool Secret's name: lowercase, `_` as `-`, as a
    * Kubernetes name must hold it.
    */
  def reading(name: String): String = name.toLowerCase(Locale.ROOT).replace('_', '-')

  /** Whether a name stands as one field where `--` separates fields: read, it is not empty, holds
    * no `--` and neither starts nor ends with `-`. Fields that do can be split back apart whatever
    * the names are, so two keys of such names never read alike across fields.
    */
  def readsAsOneField(name: String): Boolean =
    val read = reading(name)
    read.nonEmpty && !read.contains("--") && !read.startsWith("-") && !read.endsWith("-")

  /** Why a NEW tenant or tenant-db name cannot be made, None when it can. Nothing stored is judged
    * by it: a name made before the rule stays, and internal names are made by paths that do not
    * ask.
    */
  def fieldRefusal(name: String, label: String): Option[String] =
    Option.when(!readsAsOneField(name))(
      s"invalid $label '$name': a name may not start or end with '_' or hold '__', because node " +
        "ids and Secret names use '--' between names"
    )

  private val PoolNamePattern = "[a-z][a-z0-9_-]*".r

  /** Why a NEW pool name cannot be made, None when it can: lowercase letters, digits, `_` and `-`,
    * starting with a letter, at most [[MaxLength]] characters, and one field ([[readsAsOneField]]).
    * A pool may hold `-` where a tenant or a database may not, so two pools of a database can read
    * the same: `PoolSupervisor.createPool` refuses the second.
    */
  def poolNameRefusal(name: String): Option[String] =
    val lower = name.toLowerCase(Locale.ROOT)
    if !PoolNamePattern.matches(lower) || lower.length > MaxLength then
      Some(
        s"invalid pool name '$name': a pool name is 1..$MaxLength letters, digits, '_' or '-', " +
          "starting with a letter, so that it stays a name in every place it goes"
      )
    else if !readsAsOneField(lower) then
      Some(
        s"invalid pool name '$name': a name may not end with '_' or '-' or hold two of them in a " +
          "row, because node ids and Secret names use '--' between names"
      )
    else None

  /** Mint a surrogate row id with the given prefix (e.g. `t`, `td`, `p`, `r`, `g`, `rp`, `pp`,
    * `fs`, `fsec`, `u`). Format: `<prefix>-<32 lowercase hex chars>` -- a UUID with dashes
    * stripped, ~128 bits of entropy.
    */
  def newSurrogateId(prefix: String): String =
    s"$prefix-${java.util.UUID.randomUUID().toString.replace("-", "")}"
