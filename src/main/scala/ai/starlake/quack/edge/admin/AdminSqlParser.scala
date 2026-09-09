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

  // Bounded to the first few tokens: claims() runs on every statement on the FlightSQL hot
  // path and never needs more than the leading keywords to decide interception.
  private val ClaimsTokenBudget = 5

  def claims(sql: String): Boolean =
    tokenizeUpTo(sql, ClaimsTokenBudget) match
      // A tokenizer failure (unterminated quote/comment) leaves the statement unclaimed. That
      // is fail-closed downstream: DuckDB's own parser reports the syntax error, this dialect
      // never mistakenly intercepts a malformed statement it can't actually parse.
      case Left(_)     => false
      case Right(toks) =>
        def kw(k: Int): String =
          if k < toks.length && !toks(k).quoted then toks(k).upper else ""
        kw(0) match
          case "GRANT" | "REVOKE" => true
          case "CREATE"           =>
            kw(1) == "ROLE" || kw(1) == "USER" ||
            (kw(1) == "ROW" && kw(2) == "POLICY") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICY") ||
            (kw(1) == "OR" && kw(2) == "REPLACE" && (kw(3) == "ROW" || kw(3) == "COLUMN"))
          case "DROP" =>
            kw(1) == "ROLE" || kw(1) == "USER" ||
            (kw(1) == "ROW" && kw(2) == "POLICY") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICY")
          case "ALTER" => kw(1) == "GROUP" || kw(1) == "USER"
          case "SHOW"  =>
            kw(1) == "ROLES" || kw(1) == "GRANTS" || kw(1) == "USERS" ||
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

  private def tokenize(sql: String): Either[String, Vector[Tok]] = tokenizeUpTo(sql, Int.MaxValue)

  // Shared tokenizer core. `maxTokens` bounds how many tokens are collected before returning -
  // used by claims() to avoid a full-statement scan (per-token allocation and toUpperCase,
  // including on multi-megabyte string literals) on every statement of the hot path. Scanning
  // stops at the budget; the first maxTokens tokens are nonetheless correct because comment
  // handling inside the window is complete and no input past the window can affect them.
  private def tokenizeUpTo(sql: String, maxTokens: Int): Either[String, Vector[Tok]] =
    val toks                    = Vector.newBuilder[Tok]
    var i                       = 0
    val n                       = sql.length
    var count                   = 0
    var failure: Option[String] = None
    while i < n && failure.isEmpty && count < maxTokens do
      val c = sql(i)
      if c.isWhitespace then i += 1
      else if c == '-' && i + 1 < n && sql(i + 1) == '-' then
        while i < n && sql(i) != '\n' do i += 1
      else if c == '/' && i + 1 < n && sql(i + 1) == '*' then
        // DuckDB nests block comments, so a matching close requires depth-counting rather
        // than a first-match indexOf, or "/* /* */ GRANT ... */" reads as a live statement.
        var depth = 1
        var j     = i + 2
        while j < n && depth > 0 do
          if j + 1 < n && sql(j) == '/' && sql(j + 1) == '*' then { depth += 1; j += 2 }
          else if j + 1 < n && sql(j) == '*' && sql(j + 1) == '/' then { depth -= 1; j += 2 }
          else j += 1
        if depth > 0 then failure = Some("unterminated block comment") else i = j
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
          count += 1
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
          count += 1
          i = j
      else if c.isLetter || c == '_' then
        var j = i
        while j < n && (sql(j).isLetterOrDigit || sql(j) == '_' || sql(j) == '$') do j += 1
        val s = sql.substring(i, j)
        toks += Tok(s, s.toUpperCase, quoted = false, i, j)
        count += 1
        i = j
      else
        toks += Tok(c.toString, c.toString, quoted = false, i, i + 1)
        count += 1
        i += 1
    failure.toLeft(toks.result())

  private final class P(sql: String, toks: Vector[Tok]):
    private var i = 0

    private def eof: Boolean             = i >= toks.length
    private def peek(k: Int = 0): String =
      if i + k < toks.length then toks(i + k).upper else ""
    private def quotedAt(k: Int): Boolean = i + k < toks.length && toks(i + k).quoted
    private def peekQuoted: Boolean       = quotedAt(0)
    private def bump(): Unit              = i += 1

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

    /** Expects a single-quoted string literal token; returns its content with '' unescaped. */
    private def stringLiteral(what: String): Either[String, String] =
      if eof || !toks(i).raw.startsWith("'") then Left(s"expected $what as a quoted string literal")
      else
        val raw = toks(i).raw
        i += 1
        Right(raw.substring(1, raw.length - 1).replace("''", "'"))

    private def end(cmd: AdminCommand): Either[String, AdminCommand] =
      if eof then Right(cmd)
      else Left(s"unexpected trailing input: '${toks(i).raw}'")

    private def ifExistsOpt(): Boolean =
      if peek() == "IF" && !quotedAt(0) && peek(1) == "EXISTS" && !quotedAt(1) then { i += 2; true }
      else false

    private def principal(): Either[String, Principal] =
      if optKw("USER") then ident("user name").map(Principal.User.apply)
      else if optKw("GROUP") then ident("group name").map(Principal.Group.apply)
      else Left(s"expected USER or GROUP, found '${if eof then "<end>" else toks(i).raw}'")

    private val PrivWords = Set("SELECT", "INSERT", "UPDATE", "DELETE", "DDL", "ALL")

    /** Comma-separated privilege list, mapped onto the stored verb space. */
    private def privileges(): Either[String, String] =
      var privs                = Set.empty[String]
      var fail: Option[String] = None
      var more                 = true
      while more && fail.isEmpty do
        val p = peek()
        if PrivWords(p) && !peekQuoted then
          bump()
          if p == "ALL" then { optKw("PRIVILEGES"); () }
          privs += p
        else
          fail = Some(
            s"expected a privilege (SELECT, INSERT, UPDATE, DELETE, DDL, ALL), " +
              s"found '${if eof then "<end>" else toks(i).raw}'"
          )
        if fail.isEmpty then more = optKw(",")
      fail.toLeft(privs).flatMap(mapVerb)

    private def mapVerb(privs: Set[String]): Either[String, String] =
      val dml = privs.intersect(Set("INSERT", "UPDATE", "DELETE"))
      if privs.contains("ALL") then
        if privs.size == 1 then Right("ALL")
        else Left("ALL cannot be combined with other privileges")
      else if privs.contains("DDL") then
        if dml.nonEmpty || privs.contains("SELECT") then
          Left("DDL cannot be combined with other privileges; use ALL")
        else Right("DDL")
      else if dml.nonEmpty then Right("RW")
      else if privs.contains("SELECT") then Right("RO")
      else Left("empty privilege list")

    private def optDot(): Boolean =
      if peek() == "." && !peekQuoted then { i += 1; true }
      else false

    private def refSegment(): Either[String, String] =
      if !eof && !toks(i).quoted && toks(i).raw == "*" then { i += 1; Right("*") }
      else if !eof && toks(i).quoted && toks(i).raw == "*" then
        Left("quoted \"*\" is a literal identifier, not the * wildcard")
      else ident("identifier or *")

    /** 1-3 dot-separated segments; missing leading segments pad with "*". */
    private def tableRef(): Either[String, TableRef] =
      refSegment().flatMap { s1 =>
        if optDot() then
          refSegment().flatMap { s2 =>
            if optDot() then refSegment().map(s3 => TableRef(s1, s2, s3))
            else Right(TableRef("*", s1, s2))
          }
        else Right(TableRef("*", "*", s1))
      }

    private def poolTarget(): Either[String, PoolTarget] =
      ident("pool name").flatMap { first =>
        if optDot() then ident("pool name").map(p => PoolTarget(Some(first), p))
        else Right(PoolTarget(None, first))
      }

    def statement(): Either[String, AdminCommand] =
      if optKw("GRANT") then grant()
      else if optKw("REVOKE") then revoke()
      else if optKw("CREATE") then create()
      else if optKw("DROP") then drop()
      else if optKw("ALTER") then alter()
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
      else if optKw("CONNECT") then
        for
          _   <- kw("ON")
          _   <- kw("POOL")
          t   <- poolTarget()
          _   <- kw("TO")
          p   <- principal()
          out <- end(AdminCommand.GrantPool(t, p))
        yield out
      else
        for
          verb <- privileges()
          _    <- kw("ON")
          _ = optKw("TABLE")
          ref <- tableRef()
          _   <- kw("TO")
          _ = optKw("ROLE")
          role <- ident("role name")
          out  <- end(AdminCommand.GrantTable(verb, ref, role))
        yield out

    private def revoke(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          role <- ident("role name")
          _    <- kw("FROM")
          p    <- principal()
          out  <- end(AdminCommand.RevokeRoleFrom(role, p))
        yield out
      else if optKw("CONNECT") then
        for
          _   <- kw("ON")
          _   <- kw("POOL")
          t   <- poolTarget()
          _   <- kw("FROM")
          p   <- principal()
          out <- end(AdminCommand.RevokePool(t, p))
        yield out
      else
        for
          verb <- privileges()
          _    <- kw("ON")
          _ = optKw("TABLE")
          ref <- tableRef()
          _   <- kw("FROM")
          _ = optKw("ROLE")
          role <- ident("role name")
          out  <- end(
            AdminCommand.RevokeTable(if verb == "ALL" then None else Some(verb), ref, role)
          )
        yield out

    /** Requires the current token to be '('; scans the RAW sql for the balanced closing paren,
      * skipping single-quoted strings ('' escape), double-quoted identifiers, and both comment
      * forms the tokenizer accepts (line `--` and nested block `/* */`), so comment content never
      * affects paren depth or the semicolon guard below. Rejects a bare `;` in the body - it has no
      * legitimate use in an extracted expression and would break the downstream textual splice.
      * Also rejects a body whose last line comment is not followed by further real content before
      * the close: `.trim()` strips the trailing whitespace/newline that "closed" such a comment in
      * the raw source, so a naturally-formatted `USING (\n  pred -- note\n)` would otherwise leave
      * the trimmed body ending in a live, unterminated `--` - the same downstream splice hazard as
      * the semicolon case. A `--` inside a string literal is still ordinary content (tracked below
      * via lastContentEnd, not flagged as a comment start at all since inStr/inQuote take
      * priority). Returns the trimmed body and advances the token cursor past the closing paren.
      * The body is passed verbatim to the jsqlparser-based validators downstream - no
      * re-tokenization here.
      */
    private def parenExpression(): Either[String, String] =
      if eof || toks(i).quoted || toks(i).raw != "(" then Left("expected ( after USING")
      else
        val open                    = toks(i).start
        var j                       = open
        var depth                   = 0
        var inStr                   = false
        var inQuote                 = false
        var close                   = -1
        var lastContentEnd          = -1
        var lastLineCommentStart    = -1
        var failure: Option[String] = None
        while j < sql.length && close < 0 && failure.isEmpty do
          val c = sql(j)
          if inStr then
            if c == '\'' then
              if j + 1 < sql.length && sql(j + 1) == '\'' then j += 1 else inStr = false
            lastContentEnd = j
            j += 1
          else if inQuote then
            if c == '"' then inQuote = false
            lastContentEnd = j
            j += 1
          else if c == '\'' then { inStr = true; lastContentEnd = j; j += 1 }
          else if c == '"' then { inQuote = true; lastContentEnd = j; j += 1 }
          else if c == '-' && j + 1 < sql.length && sql(j + 1) == '-' then
            lastLineCommentStart = j
            while j < sql.length && sql(j) != '\n' do j += 1
          else if c == '/' && j + 1 < sql.length && sql(j + 1) == '*' then
            // Mirrors the tokenizer's nested-comment scan (see tokenizeUpTo) so a ')' or ';'
            // inside a comment is invisible to depth counting and the semicolon guard. A block
            // comment is self-terminating (always has an explicit close), so unlike a line
            // comment it never needs lastContentEnd tracking to stay safe under trim().
            var cdepth = 1
            var k      = j + 2
            while k < sql.length && cdepth > 0 do
              if k + 1 < sql.length && sql(k) == '/' && sql(k + 1) == '*' then
                cdepth += 1; k += 2
              else if k + 1 < sql.length && sql(k) == '*' && sql(k + 1) == '/' then
                cdepth -= 1; k += 2
              else k += 1
            if cdepth > 0 then failure = Some("unterminated comment in expression") else j = k
          else if c == ';' then failure = Some("semicolon not allowed in expression")
          else if c == '(' then { depth += 1; lastContentEnd = j; j += 1 }
          else if c == ')' then
            depth -= 1
            // The final close (depth back to 0) sits outside the extracted body, so it must not
            // count as trailing content - only a nested close does.
            if depth == 0 then close = j else lastContentEnd = j
            j += 1
          else
            if !c.isWhitespace then lastContentEnd = j
            j += 1
        failure match
          case Some(err) => Left(err)
          case None      =>
            if close < 0 then Left("unbalanced parentheses in expression")
            else if lastLineCommentStart >= 0 && lastLineCommentStart > lastContentEnd then
              Left("line comment at end of expression")
            else
              val body = sql.substring(open + 1, close).trim
              if body.isEmpty then Left("empty expression")
              else
                while i < toks.length && toks(i).start <= close do i += 1
                Right(body)

    private def create(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          name <- ident("role name")
          out  <- end(AdminCommand.CreateRole(name))
        yield out
      else if optKw("USER") then
        for
          name <- ident("user name")
          _ = optKw("WITH")
          _  <- kw("PASSWORD")
          pw <- stringLiteral("password")
          _  <- if pw.isEmpty then Left("password must not be empty") else Right(())
          isAdmin = optKw("ADMIN")
          out <- end(AdminCommand.CreateUser(name, pw, isAdmin))
        yield out
      else
        val orReplaceE: Either[String, Boolean] =
          if optKw("OR") then kw("REPLACE").map(_ => true) else Right(false)
        orReplaceE.flatMap { orReplace =>
          if optKw("ROW") then
            for
              _ <- kw("POLICY")
              _ <- kw("ON")
              _ = optKw("TABLE")
              ref  <- tableRef()
              _    <- kw("FOR")
              _    <- kw("ROLE")
              role <- ident("role name")
              _    <- kw("USING")
              pred <- parenExpression()
              out  <- end(AdminCommand.CreateRowPolicy(ref, role, pred, orReplace))
            yield out
          else if optKw("COLUMN") then
            for
              _ <- kw("POLICY")
              _ <- kw("ON")
              _ = optKw("TABLE")
              ref  <- tableRef()
              _    <- kw("COLUMN")
              col  <- ident("column name")
              _    <- kw("FOR")
              _    <- kw("ROLE")
              role <- ident("role name")
              cmd  <- columnAction(ref, col, role, orReplace)
            yield cmd
          else Left("expected ROLE, ROW POLICY or COLUMN POLICY after CREATE")
        }

    private def columnAction(
        ref: TableRef,
        col: String,
        role: String,
        orReplace: Boolean
    ): Either[String, AdminCommand] =
      if optKw("DENY") then
        end(AdminCommand.CreateColumnPolicy(ref, col, role, "deny", None, orReplace))
      else if optKw("MASK") then
        for
          _    <- kw("USING")
          expr <- parenExpression()
          out  <-
            end(AdminCommand.CreateColumnPolicy(ref, col, role, "mask", Some(expr), orReplace))
        yield out
      else Left("expected DENY or MASK USING (...) in column policy")

    private def drop(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        val ifE = ifExistsOpt()
        for
          name <- ident("role name")
          out  <- end(AdminCommand.DropRole(name, ifE))
        yield out
      else if optKw("USER") then
        val ifE = ifExistsOpt()
        for
          name <- ident("user name")
          out  <- end(AdminCommand.DropUser(name, ifE))
        yield out
      else if optKw("ROW") then
        for
          _ <- kw("POLICY")
          ifE = ifExistsOpt()
          _ <- kw("ON")
          _ = optKw("TABLE")
          ref  <- tableRef()
          _    <- kw("FOR")
          _    <- kw("ROLE")
          role <- ident("role name")
          out  <- end(AdminCommand.DropRowPolicy(ref, role, ifE))
        yield out
      else if optKw("COLUMN") then
        for
          _ <- kw("POLICY")
          ifE = ifExistsOpt()
          _ <- kw("ON")
          _ = optKw("TABLE")
          ref  <- tableRef()
          _    <- kw("COLUMN")
          col  <- ident("column name")
          _    <- kw("FOR")
          _    <- kw("ROLE")
          role <- ident("role name")
          out  <- end(AdminCommand.DropColumnPolicy(ref, col, role, ifE))
        yield out
      else Left("expected ROLE, ROW POLICY or COLUMN POLICY after DROP")

    private def alter(): Either[String, AdminCommand] =
      if optKw("GROUP") then
        for
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
      else if optKw("USER") then
        for
          name <- ident("user name")
          _ = optKw("WITH")
          _   <- kw("PASSWORD")
          pw  <- stringLiteral("password")
          _   <- if pw.isEmpty then Left("password must not be empty") else Right(())
          out <- end(AdminCommand.AlterUserPassword(name, pw))
        yield out
      else Left("expected GROUP or USER after ALTER")

    private def show(): Either[String, AdminCommand] =
      if optKw("ROLES") then end(AdminCommand.ShowRoles)
      else if optKw("USERS") then end(AdminCommand.ShowUsers)
      else if optKw("GRANTS") then
        for
          _   <- kw("FOR")
          _   <- kw("ROLE")
          r   <- ident("role name")
          out <- end(AdminCommand.ShowGrants(r))
        yield out
      else if optKw("ROW") then
        for
          _   <- kw("POLICIES")
          f   <- policyFilter()
          out <- end(AdminCommand.ShowRowPolicies(f))
        yield out
      else if optKw("COLUMN") then
        for
          _   <- kw("POLICIES")
          f   <- policyFilter()
          out <- end(AdminCommand.ShowColumnPolicies(f))
        yield out
      else if optKw("POOL") then
        for
          _   <- kw("GRANTS")
          p   <- if optKw("FOR") then principal().map(Some(_)) else Right(None)
          out <- end(AdminCommand.ShowPoolGrants(p))
        yield out
      else Left("not an admin SHOW form")

    private def policyFilter(): Either[String, PolicyFilter] =
      if optKw("ON") then
        optKw("TABLE")
        tableRef().map(PolicyFilter.OnTable.apply)
      else if optKw("FOR") then
        for
          _ <- kw("ROLE")
          r <- ident("role name")
        yield PolicyFilter.ForRole(r)
      else Right(PolicyFilter.All)
