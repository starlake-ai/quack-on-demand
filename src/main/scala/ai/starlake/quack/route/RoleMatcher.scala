package ai.starlake.quack.route

import ai.starlake.quack.model.{Role, StatementKind}

object RoleMatcher:

  /** Ordered list of acceptable roles for a statement, most-preferred first. */
  def preferred(kind: StatementKind): List[Role] = kind match
    case StatementKind.Select   => List(Role.ReadOnly, Role.Dual)
    case StatementKind.Dml      => List(Role.WriteOnly, Role.Dual)
    case StatementKind.Ddl      => List(Role.WriteOnly, Role.Dual)
    case StatementKind.Begin    => List(Role.WriteOnly, Role.Dual)
    case StatementKind.Commit   => List(Role.WriteOnly, Role.Dual)
    case StatementKind.Rollback => List(Role.WriteOnly, Role.Dual)
    case StatementKind.Other    => List(Role.ReadOnly, Role.Dual)

  /** Restrict `preferred` to roles actually present (used at routing time). */
  def fallback(kind: StatementKind, available: Set[Role]): List[Role] =
    preferred(kind).filter(available.contains)
