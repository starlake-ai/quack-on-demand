package ai.starlake.quack.route

import ai.starlake.quack.model.StatementKind

object StatementClassifier:

  /** Cheap keyword-based classification. JSQLParser is overkill for routing
    * decisions — we only need the verb. Returns Other if the first non-blank
    * token doesn't match a known verb. */
  def classify(sql: String): StatementKind =
    firstToken(sql).map(_.toUpperCase) match
      case Some("SELECT") | Some("WITH") | Some("VALUES") | Some("SHOW") |
           Some("DESCRIBE") | Some("EXPLAIN")                            => StatementKind.Select
      case Some("INSERT") | Some("UPDATE") | Some("DELETE") | Some("MERGE") |
           Some("UPSERT") | Some("REPLACE") | Some("COPY")               => StatementKind.Dml
      case Some("CREATE") | Some("DROP") | Some("ALTER") | Some("TRUNCATE") |
           Some("ATTACH") | Some("DETACH") | Some("COMMENT") |
           Some("GRANT")  | Some("REVOKE")                               => StatementKind.Ddl
      case Some("BEGIN") | Some("START")                                 => StatementKind.Begin
      case Some("COMMIT") | Some("END")                                  => StatementKind.Commit
      case Some("ROLLBACK") | Some("ABORT")                              => StatementKind.Rollback
      case _                                                             => StatementKind.Other

  private def firstToken(sql: String): Option[String] =
    val trimmed = sql.trim
    if trimmed.isEmpty then None
    else Some(trimmed.takeWhile(c => !c.isWhitespace && c != ';').dropWhile(_ == '('))