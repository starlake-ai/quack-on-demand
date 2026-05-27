package ai.starlake.quack.model

enum StatementKind:
  case Select
  case Dml          // INSERT / UPDATE / DELETE / MERGE
  case Ddl          // CREATE / ALTER / DROP / TRUNCATE
  case Begin
  case Commit
  case Rollback
  case Other        // SET, SHOW, PRAGMA, etc. — treated like a read by default