package ai.starlake.quack.edge.admin

/** Parser for the SQL admin dialect (spec 2026-09-09-sql-admin-dialect-design.md).
  *
  * Statement shells are hand-tokenized here; embedded expressions (row-policy predicates, mask
  * transforms) are sliced verbatim and validated downstream by the jsqlparser-based
  * RowPredicateValidator / TransformSqlValidator inside PoolSupervisor.
  *
  * `claims` decides interception: once a statement is claimed it is answered by the manager or
  * rejected - never forwarded to a DuckDB node.
  */
object AdminSqlParser:

  private final case class Tok(raw: String, upper: String, quoted: Boolean, start: Int, end: Int)

  def claims(sql: String): Boolean =
    tokenize(sql) match
      case Left(_)     => false
      case Right(toks) =>
        def kw(k: Int): String =
          if k < toks.length && !toks(k).quoted then toks(k).upper else ""
        kw(0) match
          case "GRANT" | "REVOKE" => true
          case "CREATE"           =>
            kw(1) == "ROLE" ||
            (kw(1) == "ROW" && kw(2) == "POLICY") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICY") ||
            (kw(1) == "OR" && kw(2) == "REPLACE" && (kw(3) == "ROW" || kw(3) == "COLUMN"))
          case "DROP" =>
            kw(1) == "ROLE" ||
            (kw(1) == "ROW" && kw(2) == "POLICY") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICY")
          case "ALTER" => kw(1) == "GROUP"
          case "SHOW"  =>
            kw(1) == "ROLES" || kw(1) == "GRANTS" ||
            (kw(1) == "ROW" && kw(2) == "POLICIES") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICIES") ||
            (kw(1) == "POOL" && kw(2) == "GRANTS")
          case _ => false

  def parse(sql: String): Either[String, AdminCommand] =
    tokenize(sql).flatMap { all =>
      val toks = if all.nonEmpty && all.last.raw == ";" then all.dropRight(1) else all
      if toks.isEmpty then Left("empty statement")
      else new P(sql, toks).statement()
    }

  private def tokenize(sql: String): Either[String, Vector[Tok]] =
    val toks                    = Vector.newBuilder[Tok]
    var i                       = 0
    val n                       = sql.length
    var failure: Option[String] = None
    while i < n && failure.isEmpty do
      val c = sql(i)
      if c.isWhitespace then i += 1
      else if c == '-' && i + 1 < n && sql(i + 1) == '-' then
        while i < n && sql(i) != '\n' do i += 1
      else if c == '/' && i + 1 < n && sql(i + 1) == '*' then
        val stop = sql.indexOf("*/", i + 2)
        if stop < 0 then failure = Some("unterminated block comment") else i = stop + 2
      else if c == '"' then
        val sb     = new StringBuilder
        var j      = i + 1
        var closed = false
        while j < n && !closed do
          if sql(j) == '"' then
            if j + 1 < n && sql(j + 1) == '"' then { sb.append('"'); j += 2 }
            else { closed = true; j += 1 }
          else { sb.append(sql(j)); j += 1 }
        if !closed then failure = Some("unterminated quoted identifier")
        else
          val s = sb.toString
          toks += Tok(s, s.toUpperCase, quoted = true, i, j)
          i = j
      else if c == '\'' then
        var j      = i + 1
        var closed = false
        while j < n && !closed do
          if sql(j) == '\'' then
            if j + 1 < n && sql(j + 1) == '\'' then j += 2 else { closed = true; j += 1 }
          else j += 1
        if !closed then failure = Some("unterminated string literal")
        else
          val s = sql.substring(i, j)
          toks += Tok(s, s.toUpperCase, quoted = false, i, j)
          i = j
      else if c.isLetter || c == '_' then
        var j = i
        while j < n && (sql(j).isLetterOrDigit || sql(j) == '_' || sql(j) == '$') do j += 1
        val s = sql.substring(i, j)
        toks += Tok(s, s.toUpperCase, quoted = false, i, j)
        i = j
      else
        toks += Tok(c.toString, c.toString, quoted = false, i, i + 1)
        i += 1
    failure.toLeft(toks.result())

  private final class P(sql: String, toks: Vector[Tok]):
    private var i = 0

    private def eof: Boolean             = i >= toks.length
    private def peek(k: Int = 0): String =
      if i + k < toks.length then toks(i + k).upper else ""
    private def peekQuoted: Boolean = i < toks.length && toks(i).quoted
    private def bump(): Unit        = i += 1

    private def kw(w: String): Either[String, Unit] =
      if peek() == w && !peekQuoted then { i += 1; Right(()) }
      else if eof then Left(s"expected $w at end of statement")
      else Left(s"expected $w, found '${toks(i).raw}'")

    private def optKw(w: String): Boolean =
      if peek() == w && !peekQuoted then { i += 1; true }
      else false

    private def ident(what: String): Either[String, String] =
      if eof then Left(s"expected $what at end of statement")
      else
        val t = toks(i)
        if t.quoted || t.raw.head.isLetter || t.raw.head == '_' then { i += 1; Right(t.raw) }
        else Left(s"expected $what, found '${t.raw}'")

    private def end(cmd: AdminCommand): Either[String, AdminCommand] =
      if eof then Right(cmd)
      else Left(s"unexpected trailing input: '${toks(i).raw}'")

    private def ifExistsOpt(): Boolean =
      if peek() == "IF" && peek(1) == "EXISTS" then { i += 2; true }
      else false

    private def principal(): Either[String, Principal] =
      if optKw("USER") then ident("user name").map(Principal.User.apply)
      else if optKw("GROUP") then ident("group name").map(Principal.Group.apply)
      else Left(s"expected USER or GROUP, found '${if eof then "<end>" else toks(i).raw}'")

    def statement(): Either[String, AdminCommand] =
      if optKw("GRANT") then grant()
      else if optKw("REVOKE") then revoke()
      else if optKw("CREATE") then create()
      else if optKw("DROP") then drop()
      else if optKw("ALTER") then alterGroup()
      else if optKw("SHOW") then show()
      else Left(s"not an admin statement: '${toks(0).raw}'")

    private def grant(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          role <- ident("role name")
          _    <- kw("TO")
          p    <- principal()
          out  <- end(AdminCommand.GrantRoleTo(role, p))
        yield out
      else Left("not yet implemented") // Task 2 replaces this arm

    private def revoke(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          role <- ident("role name")
          _    <- kw("FROM")
          p    <- principal()
          out  <- end(AdminCommand.RevokeRoleFrom(role, p))
        yield out
      else Left("not yet implemented") // Task 2 replaces this arm

    private def create(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          name <- ident("role name")
          out  <- end(AdminCommand.CreateRole(name))
        yield out
      else Left("not yet implemented") // Task 3 replaces this arm

    private def drop(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        val ifE = ifExistsOpt()
        for
          name <- ident("role name")
          out  <- end(AdminCommand.DropRole(name, ifE))
        yield out
      else Left("not yet implemented") // Task 3 replaces this arm

    private def alterGroup(): Either[String, AdminCommand] =
      for
        _     <- kw("GROUP")
        group <- ident("group name")
        cmd   <-
          if optKw("ADD") then
            for
              _   <- kw("USER")
              u   <- ident("user name")
              out <- end(AdminCommand.AlterGroupAddUser(group, u))
            yield out
          else if optKw("DROP") then
            for
              _   <- kw("USER")
              u   <- ident("user name")
              out <- end(AdminCommand.AlterGroupDropUser(group, u))
            yield out
          else Left("expected ADD USER or DROP USER after ALTER GROUP <name>")
      yield cmd

    private def show(): Either[String, AdminCommand] =
      Left("not yet implemented") // Task 4 replaces this
