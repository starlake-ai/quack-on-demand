package ai.starlake.quack.model

/** The shape of the "default catalog" for a tenant-db. Federation tables sit alongside this
  * default; this enum decides what the default is.
  */
sealed trait TenantDbKind {
  def wireValue: String
}

object TenantDbKind {

  case object DuckLake extends TenantDbKind {
    val wireValue = "ducklake"
  }

  case object DuckDbFile extends TenantDbKind {
    val wireValue = "duckdb-file"
  }

  case object InMemory extends TenantDbKind {
    val wireValue = "memory"
  }

  def fromWire(s: String): Either[String, TenantDbKind] = s match {
    case DuckLake.wireValue   => Right(DuckLake)
    case DuckDbFile.wireValue => Right(DuckDbFile)
    case InMemory.wireValue   => Right(InMemory)
    case other                => Left(s"unknown TenantDbKind: '$other'")
  }
}
