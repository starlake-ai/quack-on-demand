package ai.starlake.quack.ondemand.auth

import java.time.Instant

/** The scope carried by one personal access token, always relative to its owner's grants.
  *
  * `None` on a set axis means "unrestricted on this axis"; `Some(Set.empty)` means "nothing on this
  * axis". The two are deliberately different and every read path must keep them apart.
  */
final case class TokenRestriction(
    roles: Option[Set[String]],
    databases: Option[Set[String]],
    pools: Option[Set[String]],
    tools: Option[Set[String]],
    verbCeiling: Option[String],
    dropAdmin: Boolean,
    stmtTimeoutMs: Option[Int],
    maxRows: Option[Int],
    expiresAt: Option[Instant]
):
  def allowsDatabase(db: String): Boolean = databases.forall(_.contains(db))
  def allowsPool(pool: String): Boolean   = pools.forall(_.contains(pool))
  def allowsTool(tool: String): Boolean   = tools.forall(_.contains(tool))

object TokenRestriction:

  val Unrestricted: TokenRestriction =
    TokenRestriction(None, None, None, None, None, dropAdmin = false, None, None, None)

  /** Collapsed verbs a canonical grant verb covers, mirroring `PostgresAclValidator.verbCovers`.
    * This is a lattice ordered by subset, NOT a chain: `DDL` does not cover `Read`, so there is no
    * valid `DDL > RW > RO` ranking and none must be introduced.
    */
  def covers(verb: String): Set[String] = verb.toUpperCase match
    case "RO"  => Set("Read")
    case "RW"  => Set("Read", "Write")
    case "DDL" => Set("Ddl")
    case "ALL" => Set("Read", "Write", "Ddl")
    case _     => Set.empty

  /** The canonical verb whose coverage is exactly `s`, if any. The four coverage sets are closed
    * under intersection, so a clip always lands on one of them or on the empty set.
    */
  def verbOf(s: Set[String]): Option[String] =
    List("RO", "RW", "DDL", "ALL").find(v => covers(v) == s)

  /** The only constructor used at mint time. Returns the effective restriction for the child, or
    * `Left(axis)` naming the first axis on which the child tried to widen.
    */
  def narrow(
      parent: TokenRestriction,
      child: TokenRestriction
  ): Either[String, TokenRestriction] =

    def set(
        axis: String,
        p: Option[Set[String]],
        c: Option[Set[String]]
    ): Either[String, Option[Set[String]]] = (p, c) match
      case (_, None)            => Right(p)
      case (None, Some(cs))     => Right(Some(cs))
      case (Some(ps), Some(cs)) =>
        if cs.subsetOf(ps) then Right(Some(cs))
        else Left(s"$axis: not a subset of the parent's $axis")

    def num(axis: String, p: Option[Int], c: Option[Int]): Either[String, Option[Int]] =
      (p, c) match
        case (_, None)            => Right(p)
        case (None, Some(cv))     => Right(Some(cv))
        case (Some(pv), Some(cv)) =>
          if cv <= pv then Right(Some(cv)) else Left(s"$axis: may only decrease")

    def ceiling(p: Option[String], c: Option[String]): Either[String, Option[String]] =
      (p, c) match
        case (_, None)        => Right(p)
        case (None, Some(cv)) =>
          if covers(cv).isEmpty then Left("verbCeiling: unknown verb") else Right(Some(cv))
        case (Some(pv), Some(cv)) =>
          if covers(cv).isEmpty then Left("verbCeiling: unknown verb")
          else if covers(cv).subsetOf(covers(pv)) then Right(Some(cv))
          else Left("verbCeiling: would widen the parent's verb coverage")

    // Expiry always clamps rather than refusing: a child asking to outlive its
    // parent is a mistake to correct, not an attack to reject, and refusing
    // would make "no expiry requested" an error under an expiring parent.
    val expiry = (parent.expiresAt, child.expiresAt) match
      case (Some(p), Some(c)) => Some(if c.isBefore(p) then c else p)
      case (Some(p), None)    => Some(p)
      case (None, c)          => c

    for
      roles <- set("roles", parent.roles, child.roles)
      dbs   <- set("databases", parent.databases, child.databases)
      pools <- set("pools", parent.pools, child.pools)
      tools <- set("tools", parent.tools, child.tools)
      vc    <- ceiling(parent.verbCeiling, child.verbCeiling)
      to    <- num("stmtTimeoutMs", parent.stmtTimeoutMs, child.stmtTimeoutMs)
      rows  <- num("maxRows", parent.maxRows, child.maxRows)
    yield TokenRestriction(
      roles = roles,
      databases = dbs,
      pools = pools,
      tools = tools,
      verbCeiling = vc,
      dropAdmin = parent.dropAdmin || child.dropAdmin,
      stmtTimeoutMs = to,
      maxRows = rows,
      expiresAt = expiry
    )
