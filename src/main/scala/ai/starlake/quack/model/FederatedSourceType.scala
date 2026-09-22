package ai.starlake.quack.model

/** Discriminator for [[FederatedSource]]. `Sql` is the original free-form form, where the operator
  * writes the INSTALL / CREATE SECRET / ATTACH themselves. `IcebergRest` is declared through typed
  * fields instead, and the SQL is rendered by
  * `ai.starlake.quack.ondemand.federation.iceberg.IcebergSetupSql`.
  *
  * This enum lives in `model` (a leaf package that must not import `ondemand`), which is also why
  * [[FederatedSource.config]] is raw JSON text rather than a typed config object.
  */
enum FederatedSourceType(val wire: String):
  case Sql         extends FederatedSourceType("sql")
  case IcebergRest extends FederatedSourceType("iceberg_rest")

object FederatedSourceType:
  def fromWire(s: String): Option[FederatedSourceType] =
    val t = Option(s).getOrElse("").trim
    values.find(_.wire.equalsIgnoreCase(t))

  /** Lenient read path: an unknown or NULL `source_type` column reads as `Sql`, which is what every
    * pre-0038 row is.
    */
  def fromWireOrSql(s: String): FederatedSourceType = fromWire(s).getOrElse(Sql)
