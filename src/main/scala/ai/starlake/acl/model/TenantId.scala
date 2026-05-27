package ai.starlake.acl.model

/** Tenant identifier with validation and normalization.
  *
  * TenantId uses parse-only construction (no throwing apply) because tenant IDs
  * come from external input (API calls, config files) where failures are
  * expected and must be handled explicitly.
  *
  * @param id
  *   Normalized (lowercase) identifier for matching
  * @param originalId
  *   Original casing preserved for display
  */
final case class TenantId private (
    id: String,
    originalId: String
) {

  /** Normalized lowercase identifier for matching. */
  def canonical: String = id

  /** Original casing preserved for display. */
  def display: String = originalId

  override def toString: String = canonical

  override def equals(obj: Any): Boolean = obj match {
    case that: TenantId => this.id == that.id
    case _              => false
  }

  override def hashCode(): Int = id.hashCode()
}

object TenantId {

  private val ValidPattern = "^[a-zA-Z0-9_-]+$".r

  /** Parse a raw tenant ID string, validating format and normalizing to
    * lowercase.
    *
    * Valid tenant IDs contain only: alphanumeric characters, hyphens, and
    * underscores.
    *
    * @param raw
    *   The raw tenant ID string
    * @return
    *   Either an error message (Left) or a valid TenantId (Right)
    */
  def parse(raw: String): Either[String, TenantId] =
    if (raw.isEmpty) {
      Left("TenantId must be non-empty")
    } else if (!ValidPattern.matches(raw)) {
      Left(s"TenantId '$raw' is invalid: must match [a-z0-9_-]+")
    } else {
      Right(new TenantId(raw.toLowerCase, raw))
    }
}
