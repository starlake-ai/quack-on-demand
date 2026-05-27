package ai.starlake.acl.model

final case class TableRef private (
    database: String,
    schema: String,
    table: String,
    originalDatabase: String,
    originalSchema: String,
    originalTable: String
) {

  def canonical: String = s"$database.$schema.$table"

  def display: String = s"$originalDatabase.$originalSchema.$originalTable"

  override def toString: String = canonical

  override def equals(obj: Any): Boolean = obj match {
    case that: TableRef =>
      this.database == that.database &&
      this.schema == that.schema &&
      this.table == that.table
    case _ => false
  }

  override def hashCode(): Int = (database, schema, table).hashCode()
}

object TableRef {

  def apply(database: String, schema: String, table: String): TableRef =
    new TableRef(
      database = database.toLowerCase,
      schema = schema.toLowerCase,
      table = table.toLowerCase,
      originalDatabase = database,
      originalSchema = schema,
      originalTable = table
    )
}
