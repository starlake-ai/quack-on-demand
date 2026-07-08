package ai.starlake.quack.model

/** SQL literal escaping shared across the manager. Kept dependency-free so both the ondemand and
  * edge packages can use it.
  */
object SqlLiterals:

  /** DuckDB SQL string literal: wrap in single quotes, double any embedded `'`. */
  def duckdbLiteral(v: String): String =
    "'" + v.replace("'", "''") + "'"
