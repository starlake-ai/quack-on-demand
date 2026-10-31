package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.model.Names

/** Naming rules for branches (design section 3). Everything physical derives from the branch's
  * surrogate id, so recreating a discarded branch name never collides with its predecessor's
  * Postgres database, pool or data prefix.
  */
object BranchNames:

  /** Reserved pool-name prefix of branch pools; hidden from pool listings by default. */
  val PoolPrefix: String = "__br_"

  /** Suffix marker on the branch tenant-db name and on the branch data prefix. */
  val DbSuffixMarker: String = "__br_"

  private val NamePattern = "^[a-z][a-z0-9_-]{0,47}$".r

  /** `br-<32 hex>` surrogate id. */
  def newId(): String = Names.newSurrogateId("br")

  /** First 8 hex chars of the surrogate id, the per-branch discriminator. */
  def id8(branchId: String): String = branchId.stripPrefix("br-").take(8)

  def validateName(raw: String): Either[String, String] =
    val n = Option(raw).map(_.trim).getOrElse("")
    if NamePattern.matches(n) then Right(n)
    else
      Left(
        s"invalid branch name '$raw': 1..48 chars, lowercase letter first, then lowercase " +
          "letters, digits, '_' or '-'"
      )

  /** Branch tenant-db name (also its Postgres database name): the parent's name truncated so the
    * suffix always fits in 63 chars, plus `__br_<id8>`.
    */
  def tenantDbName(parentDbName: String, branchId: String): String =
    val suffix = s"$DbSuffixMarker${id8(branchId)}"
    parentDbName.take(Names.MaxLength - suffix.length) + suffix

  def poolName(branchId: String): String = s"$PoolPrefix${id8(branchId)}"

  def isBranchPool(poolName: String): Boolean = poolName.startsWith(PoolPrefix)

  /** Sibling data prefix: the parent's prefix without its trailing separator, plus `__br_<id8>/`. A
    * sibling (never nested) so the parent's orphan-file sweep never sees it.
    */
  def dataPath(parentDataPath: String, branchId: String): String =
    val trimmed = parentDataPath.stripSuffix("/").stripSuffix("\\")
    s"$trimmed$DbSuffixMarker${id8(branchId)}/"

  private val BranchPathRe =
    s"""^(.*)${java.util.regex.Pattern.quote(DbSuffixMarker)}[0-9a-f]{8}/?$$""".r

  /** The parent's prefix of a branch data path (`<parent>__br_<id8>/`), without its separator; None
    * for any other path. A branch node reads the parent's Parquet through the copied absolute
    * paths, so the branch's object-store secret covers both (ObjectStoreSecret.dataScopes).
    */
  def parentPrefix(dataPath: String): Option[String] = dataPath match
    case BranchPathRe(parent) => Some(parent)
    case _                    => None

  def isBranchDataPath(dataPath: String): Boolean = BranchPathRe.matches(dataPath)
