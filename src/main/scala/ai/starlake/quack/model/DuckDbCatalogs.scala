package ai.starlake.quack.model

/** DuckDB's built-in catalog names. Shared by the ACL attached-catalog resolver (Main.scala) and
  * the federated-alias validator, so the two can never disagree about what an alias may shadow.
  */
object DuckDbCatalogs:
  val Builtins: Set[String] = Set("memory", "system", "temp")
