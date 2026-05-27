package ai.starlake.acl.model

enum GrantTarget {
  case All
  case Database(database: String)
  case Schema(database: String, schema: String)
  case Table(database: String, schema: String, table: String)
}

object GrantTarget {

  def all: GrantTarget = GrantTarget.All

  def database(db: String): GrantTarget = GrantTarget.Database(db.toLowerCase)

  def schema(db: String, schema: String): GrantTarget = GrantTarget.Schema(db.toLowerCase, schema.toLowerCase)

  def table(db: String, schema: String, table: String): GrantTarget =
    GrantTarget.Table(db.toLowerCase, schema.toLowerCase, table.toLowerCase)
}
